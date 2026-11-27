package com.example.wolremote

// Actualisation forcée de la bibliothèque Jellyfin (port 8096).
object Jellyfin {
    fun get(st: Settings, path: String, timeout: Int = 8000): Resp {
        val key = st.get("kJellyfin").trim()
        val hd = mapOf("Authorization" to "MediaBrowser Token=\"$key\"", "X-Emby-Token" to key)
        return Net.call("GET", st.base(8096) + path, timeout = timeout, headers = hd)
    }

    fun delete(st: Settings, path: String): Resp {
        val key = st.get("kJellyfin").trim()
        val hd = mapOf("Authorization" to "MediaBrowser Token=\"$key\"", "X-Emby-Token" to key)
        return Net.call("DELETE", st.base(8096) + path, timeout = 15000, headers = hd)
    }

    fun post(st: Settings, path: String): Resp {
        val key = st.get("kJellyfin").trim()
        val hd = mapOf("Authorization" to "MediaBrowser Token=\"$key\"", "X-Emby-Token" to key)
        return Net.call("POST", st.base(8096) + path, json = "", timeout = 30000, headers = hd)
    }

    fun refresh(st: Settings): String {
        val key = st.get("kJellyfin").trim()
        if (key.isEmpty()) return "Clé API Jellyfin manquante (Réglages)."
        return try {
            if (st.active.isEmpty() && !st.reach(8096)) return "Jellyfin injoignable (PC éteint ?)."
            val hd = mapOf("Authorization" to "MediaBrowser Token=\"$key\"", "X-Emby-Token" to key)
            val r = Net.call("POST", st.base(8096) + "/Library/Refresh", json = "", timeout = 15000, headers = hd)
            if (r.ok) "Bibliothèque Jellyfin en cours d'actualisation." else "Jellyfin a refusé (HTTP " + r.code + ")" + (if (r.code == 401) " : clé API invalide, recrée-en une dans Jellyfin." else ".")
        } catch (e: Exception) {
            "Jellyfin injoignable : " + e.message
        }
    }
}
