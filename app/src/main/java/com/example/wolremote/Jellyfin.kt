package com.example.wolremote

// Actualisation forcée de la bibliothèque Jellyfin (port 8096).
object Jellyfin {
    fun refresh(st: Settings): String {
        val key = st.get("kJellyfin").trim()
        if (key.isEmpty()) return "Clé API Jellyfin manquante (Réglages)."
        return try {
            if (st.active.isEmpty() && !st.reach(8096)) return "Jellyfin injoignable (PC éteint ?)."
            val r = Net.call("POST", st.base(8096) + "/Library/Refresh?api_key=" + Net.enc(key), json = "", timeout = 15000)
            if (r.ok) "Bibliothèque Jellyfin en cours d'actualisation." else "Jellyfin a refusé (HTTP " + r.code + ")."
        } catch (e: Exception) {
            "Jellyfin injoignable : " + e.message
        }
    }
}
