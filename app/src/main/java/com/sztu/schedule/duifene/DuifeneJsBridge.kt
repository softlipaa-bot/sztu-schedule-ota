package com.sztu.schedule.duifene

import android.webkit.JavascriptInterface
import com.sztu.schedule.MainActivity

/** 只挂在本地页面的 WebView 上。远程登录页不能拿到这个对象。 */
class DuifeneJsBridge(
    private val activity: MainActivity,
) {
    @JavascriptInterface
    fun getState(): String = DuifeneRuntime.get().snapshotJson()

    @JavascriptInterface
    fun login(account: String?, password: String?, savePassword: Boolean) {
        DuifeneRuntime.get().login(account.orEmpty(), password.orEmpty(), savePassword)
    }

    @JavascriptInterface
    fun logout() {
        DuifeneRuntime.get().logout()
    }

    @JavascriptInterface
    fun refreshCourses() {
        DuifeneRuntime.get().refreshCourses()
    }

    @JavascriptInterface
    fun selectCourse(courseId: String?, classId: String?): String {
        val course = courseId?.trim().orEmpty()
        val room = classId?.trim().orEmpty()
        if (course.isEmpty() || room.isEmpty() || course.length > 64 || room.length > 64) return "课程参数无效"
        return DuifeneRuntime.get().selectCourse(course, room)
    }

    @JavascriptInterface
    fun startWatch() {
        activity.beginDuifeneWatch()
    }

    @JavascriptInterface
    fun stopWatch() {
        DuifeneRuntime.get().stopWatch("stopped")
    }

    @JavascriptInterface
    fun supplyWebPassword(password: String?, savePassword: Boolean) {
        DuifeneRuntime.get().supplyWebPassword(password.orEmpty(), savePassword)
    }

    @JavascriptInterface
    fun clearRecords(): String = DuifeneRuntime.get().clearRecords()
}
