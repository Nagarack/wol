package com.example.wolremote

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Sauvegarde des réglages, chiffrée par un mot de passe (AES-256-GCM, clé dérivée par PBKDF2).
object Backup {
    private fun rnd(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun key(pass: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass.toCharArray(), salt, 100000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun export(st: Settings, pass: String): ByteArray {
        val salt = rnd(16)
        val iv = rnd(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        val data = c.doFinal(JSONObject(st.all()).toString().toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", 1).put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(data))
            .toString().toByteArray(Charsets.UTF_8)
    }

    // Renvoie "" si tout va bien, sinon le message d'erreur.
    fun import(st: Settings, bytes: ByteArray, pass: String): String {
        return try {
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            val salt = Base64.decode(j.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(j.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(j.getString("data"), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
            val o = JSONObject(String(c.doFinal(data), Charsets.UTF_8))
            val m = LinkedHashMap<String, String>()
            for (k in o.keys()) m[k] = o.getString(k)
            st.replaceAll(m)
            ""
        } catch (e: javax.crypto.AEADBadTagException) {
            "Mot de passe incorrect."
        } catch (e: Exception) {
            "Fichier invalide."
        }
    }
}
