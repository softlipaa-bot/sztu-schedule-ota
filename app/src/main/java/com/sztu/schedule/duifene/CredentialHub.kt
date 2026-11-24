package com.sztu.schedule.duifene

/**
 * 当前学生的网页密码。本次输入留在内存里优先使用；只有勾选保存才写入 Keystore。
 * 不勾选会清掉旧的持久化密码。退出或换账号后清掉内存副本。
 */
class CredentialHub(
    private val saved: () -> WebCredential?,
    private val persist: (account: String, password: String) -> Unit,
    private val forget: () -> Unit,
) {
    private var memory: WebCredential? = null

    fun current(account: String?): WebCredential? {
        if (account.isNullOrBlank()) return null
        val held = memory
        if (held != null && held.account == account) return held
        return saved()?.takeIf { it.account == account }
    }

    fun accept(account: String, password: String, persistPassword: Boolean) {
        memory = WebCredential(account, password)
        if (persistPassword) persist(account, password) else forget()
    }

    fun clearSession() {
        memory = null
    }
}
