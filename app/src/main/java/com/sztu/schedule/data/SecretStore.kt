package com.sztu.schedule.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭证加密存储（报告 J08）：用 Android Keystore 里的 AES-GCM 密钥封装会话字符串。
 *
 * 设计取舍：
 * - 不在 Keystore 不可用时退回明文 —— `seal()` 返回 null 表示“不要持久化”，
 *   调用方应只保留内存副本并在 UI 提示需要重新登录；
 * - 不引入第三方依赖（androidx.security-crypto 未在离线构建里），直接使用系统 KeyStore；
 * - 解密失败（密钥被清除/数据损坏）返回 null，调用方按“无会话”处理。
 */
object SecretStore {
    private const val TAG = "SecretStore"
    private const val ALIAS = "sztu_secret_v1"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    @Volatile
    private var available: Boolean? = null

    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = try {
            ensureKey()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "keystore unavailable: ${t.javaClass.simpleName}")
            false
        }
        available = ok
        return ok
    }

    /** 封装失败返回 null —— 调用方必须把它当作“不可持久化”。 */
    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        if (!isAvailable()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "seal failed: ${t.javaClass.simpleName}")
            null
        }
    }

    fun open(payload: String?): String? {
        if (payload.isNullOrEmpty()) return null
        if (!isAvailable()) return null
        return try {
            val raw = Base64.decode(payload, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "open failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun ensureKey() {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (store.containsAlias(ALIAS)) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return store.getKey(ALIAS, null) as SecretKey
    }
}
