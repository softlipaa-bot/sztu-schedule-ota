package com.sztu.schedule.data.hwsys

import android.content.Context
import com.sztu.schedule.data.SecretStore
import java.security.MessageDigest

/**
 * 教学辅助系统的会话只放在 Keystore 封装后的 prefs 里。
 * 和教务 Cookie 分开；换账号只换这里的账号标记，不碰课表缓存。
 */
class HwsysSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun accountHash(): String? = prefs.getString(KEY_ACCOUNT, null)?.takeIf { it.isNotBlank() }

    fun accountName(): String = prefs.getString(KEY_NAME, "").orEmpty()

    fun hasRefresh(): Boolean = !refreshToken().isNullOrBlank()

    fun accessUsable(nowMs: Long = System.currentTimeMillis()): Boolean {
        val token = accessToken() ?: return false
        val exp = prefs.getLong(KEY_EXP, 0L)
        if (exp <= 0L) return false
        return exp - 30_000L > nowMs && jwtExpiryEpochMs(token) != null
    }

    /** 只有服务端查询成功后才算已验证。JWT 没过期只说明本地还能试。 */
    fun serverVerified(): Boolean = prefs.getBoolean(KEY_VERIFIED, false) && accessUsable()

    fun markServerVerified() {
        prefs.edit().putBoolean(KEY_VERIFIED, true).apply()
    }

    /** 能继续用，或手里还有刷新令牌。按钮据此决定要不要先去登录。 */
    fun sessionPresent(): Boolean = accessUsable() || hasRefresh()

    fun accessToken(): String? {
        val account = accountHash()
        if (account != null && account == processAccount && !processAccess.isNullOrBlank()) return processAccess
        return SecretStore.open(prefs.getString(KEY_ACCESS, null))
    }

    fun refreshToken(): String? {
        val account = accountHash()
        if (account != null && account == processAccount && processRefresh != null) return processRefresh
        return SecretStore.open(prefs.getString(KEY_REFRESH, null))
    }

    fun save(bundle: TokenBundle) {
        val exp = jwtExpiryEpochMs(bundle.accessToken) ?: return
        val previous = accountHash()
        val next = accountHashOf(bundle.userKey)
        processAccount = next
        processAccess = bundle.accessToken
        processRefresh = bundle.refreshToken.takeIf { it.isNotBlank() }
        val editor = prefs.edit()
            .putString(KEY_ACCOUNT, next)
            .putString(KEY_NAME, bundle.userName.take(40))
            .putLong(KEY_EXP, exp)
        val sealedAccess = SecretStore.seal(bundle.accessToken)
        val sealedRefresh = SecretStore.seal(bundle.refreshToken)
        if (sealedAccess == null) {
            editor.remove(KEY_ACCESS).remove(KEY_REFRESH)
            editor.putBoolean(KEY_NO_STORE, true)
        } else {
            editor.putString(KEY_ACCESS, sealedAccess)
            if (sealedRefresh != null) editor.putString(KEY_REFRESH, sealedRefresh) else editor.remove(KEY_REFRESH)
            editor.putBoolean(KEY_NO_STORE, false)
        }
        if (previous != null && previous != next) {
            editor.putString(KEY_VIEW_TERM, "")
            editor.putBoolean(KEY_VERIFIED, false)
        }
        editor.apply()
    }

    /** 认证被拒绝时丢掉令牌，但保留账号标记，这样还能看该账号上次的出勤缓存。 */
    fun clearTokens() {
        if (processAccount == accountHash()) {
            processAccess = null
            processRefresh = null
        }
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXP)
            .putBoolean(KEY_VERIFIED, false)
            .apply()
    }

    /** 仪器测试把候选令牌放进进程内存，不写明文文件。 */
    fun installProcessSession(access: String, refresh: String, userKey: String, expMs: Long) {
        val hash = accountHashOf(userKey)
        processAccount = hash
        processAccess = access
        processRefresh = refresh.takeIf { it.isNotBlank() }
        prefs.edit()
            .putString(KEY_ACCOUNT, hash)
            .putLong(KEY_EXP, expMs)
            .putBoolean(KEY_VERIFIED, false)
            .apply()
    }

    fun viewTerm(): String = prefs.getString(KEY_VIEW_TERM, "").orEmpty()

    fun setViewTerm(term: String) {
        prefs.edit().putString(KEY_VIEW_TERM, term).apply()
    }

    fun secureStorageUnavailable(): Boolean = prefs.getBoolean(KEY_NO_STORE, false)

    companion object {
        const val PREFS = "sztu_hwsys"
        private const val KEY_ACCESS = "access_sealed"
        private const val KEY_REFRESH = "refresh_sealed"
        private const val KEY_ACCOUNT = "account_hash"
        private const val KEY_NAME = "account_name"
        private const val KEY_EXP = "access_exp_ms"
        private const val KEY_VIEW_TERM = "view_term"
        private const val KEY_NO_STORE = "secure_storage_unavailable"
        private const val KEY_VERIFIED = "server_verified"

        @Volatile private var processAccount: String? = null
        @Volatile private var processAccess: String? = null
        @Volatile private var processRefresh: String? = null

        fun accountHashOf(userKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(userKey.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
