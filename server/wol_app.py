#!/usr/bin/env python3
"""
Wake-on-LAN + extinction à distance. Aucune dépendance (Python 3.8+).

  python wol_app.py server   -> interface web (à lancer sur un appareil toujours allumé :
                                Raspberry Pi, NAS, autre PC...). Ouvre http://IP:8080 sur ton téléphone.
  python wol_app.py agent    -> à lancer sur le PC à contrôler (permet de l'éteindre).
  python wol_app.py wake     -> envoie juste le paquet magique (test rapide).
"""
import argparse
import datetime
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# WOL_SERVER : ce fichier ne contient AUCUN secret. PIN, secret, MAC, IP... sont dans ~/wol_config.json
VERSION = 5
UPDATE_BASE = "https://raw.githubusercontent.com/Nagarack/wol/main/server/"
CONFIG_FILE = os.path.expanduser("~/wol_config.json")
# ================= CONFIG (valeurs par défaut, surchargées par ~/wol_config.json) =================
PC_MAC = ""      # adresse MAC du PC
PC_IP = "192.168.1.2"            # IP du PC
BROADCAST = "192.168.1.255"       # broadcast du reseau
AGENT_PORT = 8765                 # port de l'agent sur le PC
SECRET = ""
WEB_PORT = 8080                   # port de l'interface web
PIN = ""
last_wake = 0.0                   # (interne) date du dernier réveil demandé
# --- Compteur de consommation (lit LibreHardwareMonitor sur le PC) ---
LHM_PORT = 8085                   # serveur web de LibreHardwareMonitor
BASE_W = 60                       # watts « de base » : carte mère, RAM, disques, ventilos
PSU_EFF = 0.90                    # rendement de l'alimentation (pertes ~10 %)
METER_FILE = os.path.expanduser("~/wol_energy.json")
# --- Notifications (appli gratuite « ntfy » sur le téléphone, abonne-toi à ce sujet) ---
NTFY_TOPIC = ""   # laisse vide ("") pour désactiver les notifications
# --- Alertes ---
CPU_TEMP_MAX = 95                 # °C (un Ryzen 7000 peut monter à 95 °C normalement : alerte si ça dure)
GPU_TEMP_MAX = 110                # °C (point chaud de la carte graphique)
DISK_USED_MAX = 90                # pourcentage d'un disque utilisé
NIGHT_ALERT_HOUR = 3              # alerte si le PC est allumé à cette heure (None = désactivé)
DISK_TEMP_MAX = 65                # °C : alerte si un disque chauffe trop
DRIVE_LIFE_MIN = 10               # % de vie restante d'un SSD en dessous duquel on alerte
VAC_FILE = os.path.expanduser("~/wol_vacation")   # présent = mode vacances (aucune alerte)
ALERT_COOLDOWN = 6 * 3600         # secondes entre deux alertes du même type
# ==========================================


def load_config():
    """Lit ~/wol_config.json et remplace les valeurs par défaut (PIN, secret, MAC, IP, NTFY_TOPIC...)."""
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception as e:
        sys.exit(f"Fichier de configuration introuvable ou invalide ({CONFIG_FILE}) : {e}")
    for k, v in cfg.items():
        if k.isupper() and k in globals():
            globals()[k] = v
    for k in ("PC_MAC", "SECRET", "PIN"):
        if not globals()[k]:
            sys.exit(f"{k} manquant dans {CONFIG_FILE}")


def self_update():
    """Télécharge la dernière version depuis GitHub, vérifie la syntaxe, remplace ce fichier (copie .bak)."""
    with urllib.request.urlopen(UPDATE_BASE + "wol_app.py", timeout=30) as r:
        code = r.read()
    if b"WOL_SERVER" not in code:
        raise ValueError("fichier inattendu")
    compile(code, "wol_app.py", "exec")
    path = os.path.abspath(__file__)
    shutil.copy(path, path + ".bak")
    with open(path + ".new", "wb") as f:
        f.write(code)
    os.replace(path + ".new", path)


def restart_self():
    os.execv(sys.executable, [sys.executable] + sys.argv)


def send_magic_packet(mac: str, broadcast: str) -> None:
    raw = bytes.fromhex(mac.replace(":", "").replace("-", ""))
    if len(raw) != 6:
        raise ValueError("Adresse MAC invalide")
    packet = b"\xff" * 6 + raw * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for port in (9, 7):
            s.sendto(packet, (broadcast, port))


def is_online() -> bool:
    try:
        with socket.create_connection((PC_IP, AGENT_PORT), timeout=1):
            return True
    except OSError:
        return False


def remote_shutdown(path="/shutdown") -> None:
    req = urllib.request.Request(
        f"http://{PC_IP}:{AGENT_PORT}{path}",
        data=b"",
        method="POST",
        headers={"X-Secret": SECRET},
    )
    urllib.request.urlopen(req, timeout=5).read()



# ---------------- Télé Samsung (Tizen) : volume, muet, extinction via l'API réseau de la télé ----------------
TV_FILE = os.path.expanduser("~/wol_tv.json")


def _tv_tokens():
    try:
        with open(TV_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


class _WS:
    """Client WebSocket minimal (stdlib uniquement)."""

    def __init__(self, ip, port, path, use_ssl, timeout):
        import base64
        import ssl
        raw = socket.create_connection((ip, port), timeout=timeout)
        if use_ssl:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            raw = ctx.wrap_socket(raw, server_hostname=ip)
        self.s = raw
        self.buf = b""
        key = base64.b64encode(os.urandom(16)).decode()
        raw.sendall((f"GET {path} HTTP/1.1\r\nHost: {ip}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                     f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        while b"\r\n\r\n" not in self.buf:
            self.buf += self._recv()
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise Exception("la télé a refusé la connexion")

    def _recv(self):
        d = self.s.recv(4096)
        if not d:
            raise Exception("connexion fermée par la télé")
        return d

    def _need(self, n):
        while len(self.buf) < n:
            self.buf += self._recv()
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def read(self):
        b0, b1 = self._need(2)
        n = b1 & 127
        if n == 126:
            n = int.from_bytes(self._need(2), "big")
        elif n == 127:
            n = int.from_bytes(self._need(8), "big")
        return b0 & 15, self._need(n)

    def send(self, text):
        p = text.encode()
        mask = os.urandom(4)
        n = len(p)
        head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, "big"))
        self.s.sendall(head + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(p)))

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


def tv_keys(ip, keys, port=8002, use_ssl=True):
    """Envoie des touches de télécommande à une télé Samsung. Premier usage : accepter la demande sur la télé."""
    import base64
    toks = _tv_tokens()
    token = toks.get(ip, "")
    name = base64.b64encode(b"Mon PC").decode()
    path = "/api/v2/channels/samsung.remote.control?name=" + name + ("&token=" + token if token else "")
    ws = _WS(ip, port, path, use_ssl, 35 if not token else 10)
    try:
        while True:
            op, data = ws.read()
            if op != 1:
                continue
            m = json.loads(data.decode("utf-8", "ignore"))
            ev = m.get("event", "")
            if ev == "ms.channel.connect":
                t = (m.get("data") or {}).get("token")
                if t and t != token:
                    toks[ip] = t
                    with open(TV_FILE, "w") as f:
                        json.dump(toks, f)
                break
            if ev in ("ms.channel.timeOut", "ms.error"):
                raise Exception("connexion refusée : accepte la demande sur la télé")
        for k in keys:
            ws.send(json.dumps({"method": "ms.remote.control", "params": {"Cmd": "Click", "DataOfCmd": k, "Option": "false", "TypeOfRemote": "SendRemoteKey"}}))
            time.sleep(0.25)
    finally:
        ws.close()


# ---------------- Minuteur d'arrêt : télé / PC (tourne sur la tablette, même appli fermée) ----------------
SLEEP_FILE = os.path.expanduser("~/wol_sleep.json")
sleep_plan = None          # plan actif (ou None)
SLEEP_GRACE = 60           # secondes de préavis (notification) avant l'exécution


def _jf(path, key, method="GET"):
    req = urllib.request.Request(f"http://{PC_IP}:8096{path}", method=method, data=(b"" if method == "POST" else None),
                                 headers={"X-Emby-Token": key, "Authorization": f'MediaBrowser Token="{key}"'})
    raw = urllib.request.urlopen(req, timeout=6).read()
    return json.loads(raw) if raw else None


def jf_playing(key):
    out = []
    for x in _jf("/Sessions", key) or []:
        it = x.get("NowPlayingItem")
        if it:
            out.append({"sid": x.get("Id"), "item": it.get("Id"), "paused": bool((x.get("PlayState") or {}).get("IsPaused"))})
    return out


def sleep_save():
    try:
        with open(SLEEP_FILE, "w") as f:
            json.dump(sleep_plan, f)
    except Exception:
        pass


def sleep_load():
    global sleep_plan
    try:
        with open(SLEEP_FILE) as f:
            sleep_plan = json.load(f)
    except Exception:
        sleep_plan = None


def sleep_start(d):
    global sleep_plan
    mode = d.get("mode")
    val = int(d.get("value") or 0)
    now = time.time()
    p = {"mode": mode, "value": val, "actions": [a for a in d.get("actions", []) if a in ("stop", "screens", "shutdown", "tv")],
         "key": d.get("jf_key", ""), "tv_ip": d.get("tv_ip", ""), "created": now, "seen": [], "idle_since": None, "armed": False, "countdown": None}
    if mode == "timer":
        p["deadline"] = now + val * 60
    sleep_plan = p
    sleep_save()


def sleep_status():
    p = sleep_plan
    if not p:
        return {"active": False}
    now = time.time()
    r = {"active": True, "mode": p["mode"], "value": p["value"], "actions": p["actions"], "seen": len(p.get("seen", []))}
    if p["mode"] == "timer":
        r["remaining"] = max(0, int(p["deadline"] - now))
    if p.get("countdown"):
        r["countdown"] = max(0, int(p["countdown"] - now))
    return r


def sleep_run(p):
    key = p.get("key", "")
    if "stop" in p["actions"] and key:
        try:
            for x in jf_playing(key):
                _jf(f"/Sessions/{x['sid']}/Playing/Stop", key, "POST")
        except Exception:
            pass
    if "screens" in p["actions"] and "shutdown" not in p["actions"]:
        try:
            req = urllib.request.Request(f"http://{PC_IP}:{AGENT_PORT}/screens/off", data=b"", method="POST", headers={"X-Secret": SECRET})
            urllib.request.urlopen(req, timeout=10).read()
        except Exception:
            pass
    if "tv" in p["actions"] and p.get("tv_ip"):
        try:
            tv_keys(p["tv_ip"], ["KEY_POWER"])
        except Exception:
            pass
    if "shutdown" in p["actions"]:
        try:
            remote_shutdown()
        except Exception:
            pass


def sleep_loop():
    global sleep_plan
    sleep_load()
    while True:
        time.sleep(10)
        p = sleep_plan
        if not p:
            continue
        now = time.time()
        try:
            if p.get("countdown"):
                if now >= p["countdown"]:
                    sleep_plan = None
                    sleep_save()
                    sleep_run(p)
                continue
            if not is_online():
                sleep_plan = None      # PC déjà éteint : rien à programmer
                sleep_save()
                continue
            trigger = False
            mode = p["mode"]
            if mode == "timer":
                trigger = now >= p["deadline"]
            else:
                try:
                    play = jf_playing(p["key"]) if p.get("key") else []
                except Exception:
                    continue
                active = [x for x in play if not x["paused"]]
                if mode == "end":
                    if play:
                        p["armed"] = True
                        p["idle_since"] = None
                    elif p.get("armed"):
                        p["idle_since"] = p.get("idle_since") or now
                        trigger = now - p["idle_since"] >= 60
                elif mode == "episodes":
                    for x in play:
                        if x["item"] not in p["seen"]:
                            p["seen"].append(x["item"])
                            if len(p["seen"]) > p["value"]:
                                try:
                                    _jf(f"/Sessions/{x['sid']}/Playing/Stop", p["key"], "POST")
                                except Exception:
                                    pass
                                trigger = True
                    if not play and len(p["seen"]) >= p["value"]:
                        p["idle_since"] = p.get("idle_since") or now
                        trigger = trigger or now - p["idle_since"] >= 60
                    elif play:
                        p["idle_since"] = None
                elif mode == "idle":
                    if active:
                        p["idle_since"] = None
                    else:
                        p["idle_since"] = p.get("idle_since") or now
                        trigger = now - p["idle_since"] >= p["value"] * 60
                sleep_save()
            if trigger:
                p["countdown"] = now + SLEEP_GRACE
                sleep_save()
                notify("sleep", "Extinction programmée", f"Le PC va s'éteindre dans {SLEEP_GRACE} s. Ouvre l'appli pour annuler.", "zzz", cooldown=0)
        except Exception:
            pass


# ---------------- Compteur de consommation + alertes (tourne en continu sur la tablette) ----------------
power_state = {"ok": False, "online": False}
energy = {"days": {}}
_lock = threading.Lock()
_last_alert = {}
_hot = {"cpu": 0, "gpu": 0}
vacation = os.path.exists(VAC_FILE)
GROUPS = {"Temperatures", "Load", "Powers", "Clocks", "Voltages", "Fans", "Data", "Throughput", "Factors", "Levels", "Controls"}
HW_KEYS = ("ryzen", "radeon", "nvidia", "geforce", "intel")


def notify(kind, title, msg, tags="warning", cooldown=None):
    """Envoie une notification ntfy (au plus une fois par « cooldown » pour chaque type)."""
    if not NTFY_TOPIC or vacation:
        return
    now = time.time()
    if now - _last_alert.get(kind, 0) < (ALERT_COOLDOWN if cooldown is None else cooldown):
        return
    _last_alert[kind] = now

    def _send():
        try:
            req = urllib.request.Request(f"https://ntfy.sh/{NTFY_TOPIC}", data=msg.encode("utf-8"),
                                         method="POST", headers={"Title": title, "Tags": tags})
            urllib.request.urlopen(req, timeout=10).read()
        except Exception:
            pass
    threading.Thread(target=_send, daemon=True).start()


def _num(v, unit):
    v = str(v).strip()
    if not v.endswith(unit) or (unit == "W" and v.endswith("kW")):
        return None
    try:
        return float(v[:-len(unit)].strip().replace(",", "."))
    except ValueError:
        return None


def _walk(n, depth, hw, hw2, grp, out):
    text = n.get("Text", "")
    if depth == 2:
        hw2 = text
    if any(k in text.lower() for k in HW_KEYS):
        hw = text
    if text in GROUPS:
        grp = text
    kids = n.get("Children") or []
    if not kids and str(n.get("Value", "")).strip():
        out.append((hw or hw2, grp, text, n.get("Value", "")))
    for c in kids:
        _walk(c, depth + 1, hw, hw2, grp, out)


def read_sensors():
    with urllib.request.urlopen(f"http://{PC_IP}:{LHM_PORT}/data.json", timeout=3) as r:
        tree = json.loads(r.read())
    out = []
    _walk(tree, 0, "", "", "", out)
    cpu_hw = lambda h: any(k in h.lower() for k in ("ryzen", "intel"))
    gpu_hw = lambda h: any(k in h.lower() for k in ("radeon", "nvidia", "geforce")) and "radeon(tm)" not in h.lower()
    cpu = next((_num(v, "W") for h, g, t, v in out if cpu_hw(h) and t.lower() == "package" and _num(v, "W") is not None), None)
    gp = [(t, _num(v, "W")) for h, g, t, v in out if gpu_hw(h) and g == "Powers" and _num(v, "W") is not None]
    gpu = next((w for t, w in gp if "package" in t.lower() or "board" in t.lower()), None)
    if gpu is None and gp:
        gpu = max(w for t, w in gp)
    if cpu is None and gpu is None:
        raise ValueError("aucun capteur de puissance")
    cts = [_num(v, "°C") for h, g, t, v in out if cpu_hw(h) and g == "Temperatures" and "distance" not in t.lower() and "tjmax" not in t.lower()]
    cts = [x for x in cts if x is not None]
    gts = [_num(v, "°C") for h, g, t, v in out if gpu_hw(h) and g == "Temperatures"]
    gts = [x for x in gts if x is not None]
    disks = [(h, _num(v, "%")) for h, g, t, v in out if g == "Load" and t == "Used Space" and _num(v, "%") is not None]
    drives = []
    for n in sorted({h for h, _ in disks}):
        ts = [x for x in (_num(v, "°C") for h, g, t, v in out if h == n and g == "Temperatures") if x is not None]
        life = None
        for h, g, t, v in out:
            x = _num(v, "%") if (h == n and g == "Levels") else None
            if x is None:
                continue
            if "remaining life" in t.lower():
                life = x
            elif "percentage used" in t.lower() and life is None:
                life = 100 - x
        drives.append({"name": n, "temp": max(ts) if ts else None, "life": life})
    return {"cpu": cpu or 0.0, "gpu": gpu or 0.0, "cpu_t": max(cts) if cts else None,
            "gpu_t": max(gts) if gts else None, "disks": disks, "drives": drives}


def check_alerts(s):
    # Températures : alerte seulement si ça dure (~3 min)
    for key, val, lim, name in (("cpu", s["cpu_t"], CPU_TEMP_MAX, "processeur"), ("gpu", s["gpu_t"], GPU_TEMP_MAX, "carte graphique")):
        if val is not None and val >= lim:
            _hot[key] += 1
            if _hot[key] >= 36:
                notify("temp_" + key, "PC : temperature elevee", f"Le {name} est a {val:.0f} °C depuis plusieurs minutes.", "fire")
        else:
            _hot[key] = 0
    for name, pct in s["disks"]:
        if pct >= DISK_USED_MAX:
            notify("disk_" + name, "PC : disque presque plein", f"{name} est rempli a {pct:.0f} %.", "floppy_disk")
    for d in s.get("drives", []):
        if d["temp"] is not None and d["temp"] >= DISK_TEMP_MAX:
            notify("dtemp_" + d["name"], "PC : disque trop chaud", f"{d['name']} est a {d['temp']:.0f} °C.", "fire")
        if d["life"] is not None and d["life"] <= DRIVE_LIFE_MIN:
            notify("dlife_" + d["name"], "PC : disque usé", f"{d['name']} : il reste {d['life']:.0f} % de vie. Pense à sauvegarder.", "floppy_disk", cooldown=7 * 86400)
    if NIGHT_ALERT_HOUR is not None and datetime.datetime.now().hour == NIGHT_ALERT_HOUR:
        notify("night", "PC allume en pleine nuit", "Le PC est toujours allume. Pense a l'eteindre depuis l'appli.", "crescent_moon", cooldown=20 * 3600)


def meter_loop():
    global power_state
    try:
        with open(METER_FILE) as f:
            energy.update(json.load(f))
    except Exception:
        pass
    last = 0.0
    saved = time.time()
    tick = time.time()
    while True:
        time.sleep(5)
        # Durée des sessions : début à l'allumage, fin à l'extinction (conservé dans le fichier du compteur)
        nowt = time.time()
        dt = nowt - tick
        tick = nowt
        up = is_online()
        with _lock:
            if up:
                if not energy.get("sess_start"):
                    energy["sess_start"] = nowt
                if dt <= 30:
                    hd = energy.setdefault("hours", {})
                    dk = datetime.date.today().isoformat()
                    hd[dk] = hd.get(dk, 0.0) + dt / 3600.0
            elif energy.get("sess_start"):
                rec = {"start": energy["sess_start"], "end": nowt, "secs": max(0, nowt - energy["sess_start"]), "wh": round(energy.get("sess_wh", 0.0), 1)}
                energy["last_sess"] = rec
                energy.setdefault("sessions", []).append(rec)
                energy["sessions"] = energy["sessions"][-40:]
                energy["sess_start"] = None
                energy["sess_wh"] = 0.0
        if time.time() - saved > 60:
            try:
                with _lock, open(METER_FILE, "w") as f:
                    json.dump(energy, f)
            except Exception:
                pass
            saved = time.time()
        if not is_online():
            last = 0.0
            power_state = {"ok": False, "online": False}
            continue
        try:
            s = read_sensors()
            total = (s["cpu"] + s["gpu"] + BASE_W) / PSU_EFF
            now = time.time()
            if last and now - last <= 30:
                day = datetime.date.today().isoformat()
                with _lock:
                    energy["days"][day] = energy["days"].get(day, 0.0) + total * (now - last) / 3600.0
                    if energy.get("sess_start"):
                        energy["sess_wh"] = energy.get("sess_wh", 0.0) + total * (now - last) / 3600.0
            last = now
            disk = max(s["disks"], key=lambda x: x[1]) if s["disks"] else None
            power_state = {"ok": True, "online": True, "watts": round(total), "cpu": round(s["cpu"]),
                           "gpu": round(s["gpu"]), "base": BASE_W,
                           "cpu_t": None if s["cpu_t"] is None else round(s["cpu_t"]),
                           "gpu_t": None if s["gpu_t"] is None else round(s["gpu_t"]),
                           "disk_used": None if disk is None else round(disk[1]),
                           "disk_name": None if disk is None else disk[0],
                           "drives": s["drives"]}
            check_alerts(s)
        except Exception:
            last = 0.0
            power_state = {"ok": False, "online": True}


def power_report():
    today = datetime.date.today()
    with _lock:
        days = dict(energy["days"])
    month = today.strftime("%Y-%m")
    r = dict(power_state)
    r["vacation"] = vacation
    r["today_wh"] = round(days.get(today.isoformat(), 0.0), 1)
    r["month_wh"] = round(sum(v for k, v in days.items() if k.startswith(month)), 1)
    r["total_wh"] = round(sum(days.values()), 1)
    with _lock:
        ss = energy.get("sess_start")
        ls = energy.get("last_sess")
        hrs = dict(energy.get("hours", {}))
        swh = energy.get("sess_wh", 0.0)
        slist = list(energy.get("sessions", []))[-12:]
    r["session_s"] = int(time.time() - ss) if ss else None
    r["last_sess_s"] = int(ls["secs"]) if ls else None
    r["session_wh"] = round(swh, 1) if ss else None
    r["last_sess_wh"] = ls.get("wh") if ls else None
    r["sessions"] = [[int(x["start"]), int(x["secs"]), x.get("wh", 0.0)] for x in reversed(slist)]
    r["hours_today"] = round(hrs.get(today.isoformat(), 0.0), 2)
    r["hours_month"] = round(sum(v for k, v in hrs.items() if k.startswith(month)), 1)
    hist = []
    for i in range(29, -1, -1):
        d = (today - datetime.timedelta(days=i)).isoformat()
        hist.append([d, round(days.get(d, 0.0), 1)])
    r["history"] = hist
    return r


# ---------------- Agent (sur le PC) ----------------
class AgentHandler(BaseHTTPRequestHandler):
    def _reply(self, code, text):
        self.send_response(code)
        self.end_headers()
        self.wfile.write(text.encode())

    def do_GET(self):
        self._reply(200, "ok")

    def do_POST(self):
        if self.headers.get("X-Secret") != SECRET:
            return self._reply(403, "interdit")
        if self.path != "/shutdown":
            return self._reply(404, "inconnu")
        system = platform.system()
        if system == "Windows":
            cmd = ["shutdown", "/s", "/t", "5"]
        else:  # Linux / macOS (lancer l'agent avec sudo)
            cmd = ["shutdown", "-h", "now"]
        self._reply(200, "extinction")
        subprocess.Popen(cmd)

    def log_message(self, *a):
        pass


# ---------------- Serveur web ----------------
HTML = """<!doctype html>
<html lang="fr"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mon PC</title>
<style>
  :root { --bg:#f5f5f4; --fg:#1c1917; --card:#fff; --muted:#78716c; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#1c1917; --fg:#fafaf9; --card:#292524; --muted:#a8a29e; }
  }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         font-family:system-ui,sans-serif; background:var(--bg); color:var(--fg); }
  .card { background:var(--card); padding:28px; border-radius:20px; width:min(340px,90vw);
          box-shadow:0 4px 24px rgba(0,0,0,.1); text-align:center; }
  h1 { margin:0 0 6px; font-size:22px; }
  .status { color:var(--muted); margin-bottom:22px; display:flex; gap:8px;
            align-items:center; justify-content:center; }
  .dot { width:12px; height:12px; border-radius:50%; background:#a8a29e; }
  .dot.on { background:#16a34a; } .dot.off { background:#dc2626; }
  input { width:100%; box-sizing:border-box; padding:12px; margin-bottom:14px; font-size:16px;
          border-radius:12px; border:1px solid var(--muted); background:transparent; color:var(--fg);
          text-align:center; }
  button { width:100%; padding:16px; margin-bottom:10px; font-size:17px; border:0;
           border-radius:14px; color:#fff; font-weight:600; }
  .wake { background:#16a34a; } .off { background:#dc2626; }
  button:active { opacity:.7; }
  #msg { color:var(--muted); min-height:20px; font-size:14px; }
</style></head><body>
<div class="card">
  <h1>Mon PC</h1>
  <div class="status"><span id="dot" class="dot"></span><span id="st">Vérification…</span></div>
  <input id="pin" type="password" inputmode="numeric" placeholder="Code PIN">
  <button class="wake" onclick="act('wake')">⏻ Allumer</button>
  <button class="off" onclick="act('shutdown')">Éteindre</button>
  <div id="msg"></div>
</div>
<script>
async function refresh(){
  try{
    const r = await (await fetch('/status')).json();
    document.getElementById('dot').className = 'dot ' + (r.online ? 'on' : 'off');
    document.getElementById('st').textContent = r.online ? 'Allumé' : 'Éteint';
  }catch(e){ document.getElementById('st').textContent = 'Serveur injoignable'; }
}
async function act(a){
  if(a==='shutdown' && !confirm('Éteindre le PC ?')) return;
  const msg = document.getElementById('msg');
  msg.textContent = '…';
  try{
    const r = await fetch('/'+a, {method:'POST',
      body: JSON.stringify({pin: document.getElementById('pin').value})});
    const j = await r.json();
    msg.textContent = j.message;
  }catch(e){ msg.textContent = 'Erreur réseau'; }
  setTimeout(refresh, 2000);
}
refresh(); setInterval(refresh, 4000);
</script></body></html>"""


class WebHandler(BaseHTTPRequestHandler):
    def _json(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/status":
            return self._json(200, {"online": is_online()})
        if self.path == "/power":
            return self._json(200, power_report())
        if self.path == "/sleep/status":
            return self._json(200, sleep_status())
        if self.path == "/consume-wake":  # appelé par le PC au démarrage
            global last_wake
            if self.headers.get("X-Secret") != SECRET:
                return self._json(403, {"message": "interdit"})
            pending = time.time() - last_wake < 300
            last_wake = 0.0
            return self._json(200, {"pending": pending})
        if self.path == "/version":
            return self._json(200, {"version": VERSION})
        if self.path == "/agent-version":
            try:
                with urllib.request.urlopen(f"http://{PC_IP}:{AGENT_PORT}/version", timeout=4) as r:
                    return self._json(200, json.loads(r.read()))
            except Exception:
                return self._json(200, {"version": None})
        if self.path == "/vacation":
            return self._json(200, {"on": vacation})
        if self.path == "/docker/status":
            try:
                with urllib.request.urlopen(f"http://{PC_IP}:{AGENT_PORT}/docker/status", timeout=4) as r:
                    return self._json(200, json.loads(r.read()))
            except Exception:
                return self._json(200, {"state": "unreachable"})
        if self.path == "/game/status":
            try:
                with urllib.request.urlopen(f"http://{PC_IP}:{AGENT_PORT}/game/status", timeout=3) as r:
                    return self._json(200, json.loads(r.read()))
            except Exception:
                return self._json(200, {"running": False})
        body = HTML.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        global last_wake, vacation, sleep_plan
        length = int(self.headers.get("Content-Length", 0))
        try:
            data = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            data = {}
        if PIN and data.get("pin") != PIN:
            return self._json(403, {"message": "Code PIN incorrect"})
        try:
            if self.path in ("/game/start", "/game/stop"):
                req = urllib.request.Request(f"http://{PC_IP}:{AGENT_PORT}{self.path}", data=b"",
                                             method="POST", headers={"X-Secret": SECRET})
                data = json.loads(urllib.request.urlopen(req, timeout=20).read())
                msgs = {"already": "Chroniques est déjà allumé",
                        "started": "Démarrage de Chroniques…",
                        "stopped": "Chroniques arrêté",
                        "nothing": "Chroniques n'était pas allumé"}
                return self._json(200, {"message": msgs.get(data.get("result"), "OK")})
            if self.path == "/self-update":
                try:
                    self_update()
                except Exception as e:
                    return self._json(500, {"message": f"Échec de la mise à jour : {e}"})
                self._json(200, {"message": "Tablette mise à jour, redémarrage…"})
                threading.Timer(1.5, restart_self).start()
                return
            if self.path == "/agent-update":
                req = urllib.request.Request(f"http://{PC_IP}:{AGENT_PORT}/self-update", data=b"",
                                             method="POST", headers={"X-Secret": SECRET})
                res = json.loads(urllib.request.urlopen(req, timeout=60).read())
                msgs = {"updated": "Agent du PC mis à jour, redémarrage dans quelques secondes",
                        "invalid": "Fichier téléchargé invalide, rien n'a changé",
                        "error": "Le PC n'a pas pu télécharger la mise à jour"}
                return self._json(200, {"message": msgs.get(res.get("result"), "OK")})
            if self.path == "/vacation":
                vacation = bool(data.get("on"))
                if vacation:
                    open(VAC_FILE, "w").close()
                elif os.path.exists(VAC_FILE):
                    os.remove(VAC_FILE)
                return self._json(200, {"message": "Mode vacances " + ("activé" if vacation else "désactivé")})
            if self.path == "/tv/key":
                ip = str(data.get("ip", "")).strip()
                keys = [k for k in data.get("keys", []) if isinstance(k, str) and k.startswith("KEY_")][:20]
                if not ip or not keys:
                    return self._json(400, {"message": "Adresse de la télé manquante"})
                try:
                    tv_keys(ip, keys)
                except Exception as e:
                    return self._json(200, {"ok": False, "message": f"Télé injoignable ou refus : {e}"})
                return self._json(200, {"ok": True, "message": "OK"})
            if self.path == "/sleep/set":
                if data.get("mode") not in ("timer", "end", "episodes", "idle"):
                    return self._json(400, {"message": "Mode inconnu"})
                sleep_start(data)
                return self._json(200, {"message": "Minuteur programmé"})
            if self.path == "/sleep/cancel":
                sleep_plan = None
                sleep_save()
                return self._json(200, {"message": "Minuteur annulé"})
            if self.path == "/screens/off":
                req = urllib.request.Request(f"http://{PC_IP}:{AGENT_PORT}/screens/off", data=b"",
                                             method="POST", headers={"X-Secret": SECRET})
                res = json.loads(urllib.request.urlopen(req, timeout=10).read())
                if res.get("result") == "notask":
                    return self._json(200, {"message": "Tâche écrans non installée sur le PC (voir AJOUTER_ECRANS.ps1)"})
                return self._json(200, {"message": "Écrans éteints (ils se rallument au moindre mouvement de souris)"})
            if self.path == "/docker/update":
                req = urllib.request.Request(f"http://{PC_IP}:{AGENT_PORT}/docker/update", data=b"",
                                             method="POST", headers={"X-Secret": SECRET})
                res = json.loads(urllib.request.urlopen(req, timeout=20).read())
                msgs = {"started": "Mise à jour lancée", "busy": "Une mise à jour est déjà en cours",
                        "nodir": "Dossier docker compose introuvable"}
                return self._json(200, {"message": msgs.get(res.get("result"), "OK"), "result": res.get("result")})
            if self.path == "/wake":
                send_magic_packet(PC_MAC, BROADCAST)
                last_wake = time.time()
                return self._json(200, {"message": "Paquet envoyé, le PC démarre…"})
            if self.path == "/restart":
                last_wake = time.time()   # pour que le PC se reconnecte tout seul après le redémarrage
                remote_shutdown("/restart")
                return self._json(200, {"message": "Redémarrage en cours…"})
            if self.path == "/shutdown":
                remote_shutdown()
                return self._json(200, {"message": "Extinction en cours…"})
        except Exception as e:
            return self._json(500, {"message": f"Échec : {e}"})
        self._json(404, {"message": "Inconnu"})

    def log_message(self, *a):
        pass


def main():
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["server", "agent", "wake"])
    mode = p.parse_args().mode
    load_config()
    if mode == "wake":
        send_magic_packet(PC_MAC, BROADCAST)
        print("Paquet magique envoyé.")
    elif mode == "agent":
        print(f"Agent en écoute sur le port {AGENT_PORT}")
        ThreadingHTTPServer(("0.0.0.0", AGENT_PORT), AgentHandler).serve_forever()
    else:
        threading.Thread(target=meter_loop, daemon=True).start()
        threading.Thread(target=sleep_loop, daemon=True).start()
        print(f"Interface sur http://0.0.0.0:{WEB_PORT}")
        ThreadingHTTPServer(("0.0.0.0", WEB_PORT), WebHandler).serve_forever()


if __name__ == "__main__":
    main()
