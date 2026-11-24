package com.sztu.schedule.duifene

import android.content.Context
import com.sztu.schedule.data.SecretStore
import org.json.JSONObject

/** 对分易凭证单独放在 sztu_duifene，经 Keystore 封装。Keystore 不可用时不落明文。 */
class DuifeneVault(context: Context) : AppSessionStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var memorySession: Map<String, String>? = null
    private var memoryPassword: WebCredential? = null

    fun storageWarning(): Boolean = prefs.getBoolean(KEY_NO_STORE, false) || !SecretStore.isAvailable()

    override fun load(): Map<String, String>? {
        memorySession?.let { return it }
        val opened = SecretStore.open(prefs.getString(KEY_SESSION, null)) ?: return null
        return jsonToMap(opened)?.also { memorySession = it }
    }

    override fun save(values: Map<String, String>) {
        memorySession = values
        val sealed = SecretStore.seal(mapToJson(values))
        val editor = prefs.edit()
        if (sealed == null) {
            editor.remove(KEY_SESSION).putBoolean(KEY_NO_STORE, true)
        } else {
            editor.putString(KEY_SESSION, sealed).putBoolean(KEY_NO_STORE, false)
        }
        editor.apply()
    }

    override fun clear() {
        memorySession = null
        prefs.edit().remove(KEY_SESSION).apply()
    }

    fun savePassword(account: String, password: String) {
        memoryPassword = WebCredential(account, password)
        val payload = JSONObject().put("account", account).put("password", password).toString()
        val sealed = SecretStore.seal(payload)
        val editor = prefs.edit()
        if (sealed == null) editor.remove(KEY_PASSWORD).putBoolean(KEY_NO_STORE, true)
        else editor.putString(KEY_PASSWORD, sealed).putBoolean(KEY_NO_STORE, false)
        editor.apply()
    }

    fun clearPassword() {
        memoryPassword = null
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    fun password(): WebCredential? {
        memoryPassword?.let { return it }
        val opened = SecretStore.open(prefs.getString(KEY_PASSWORD, null)) ?: return null
        return try {
            val obj = JSONObject(opened)
            val account = obj.optString("account")
            val secret = obj.optString("password")
            if (account.isEmpty() || secret.isEmpty()) null
            else WebCredential(account, secret).also { memoryPassword = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToJson(values: Map<String, String>): String {
        val obj = JSONObject()
        values.forEach { (key, value) -> obj.put(key, value) }
        return obj.toString()
    }

    private fun jsonToMap(raw: String): Map<String, String>? = try {
        val obj = JSONObject(raw)
        obj.keys().asSequence().associateWith { key -> obj.optString(key) }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PREFS = "sztu_duifene"
        private const val KEY_SESSION = "session"
        private const val KEY_PASSWORD = "password"
        private const val KEY_NO_STORE = "no_store"
    }
}
