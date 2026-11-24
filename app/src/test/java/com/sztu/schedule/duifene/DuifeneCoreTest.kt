package com.sztu.schedule.duifene

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DuifeneCoreTest {
    @Test
    fun defaultsMatchUpstreamAndDiscoveryIsNotPointTwo() {
        val settings = DuifeneSettings()
        assertEquals(3.0, settings.pollMinS, 0.0)
        assertEquals(5.0, settings.pollMaxS, 0.0)
        assertEquals(6, settings.monitorStartHour)
        assertEquals(23, settings.monitorEndHour)
        assertEquals(0.20, settings.headcountRatio, 0.0)
        assertEquals(5, settings.smallClassMax)
        assertEquals(300.0, settings.cooldownSeconds, 0.0)
        assertEquals(0.2, DuifenePolicy.NUMERIC_POLL_S, 0.0)
        assertEquals(5.0, DuifenePolicy.NUMERIC_PRIORITY_WINDOW_S, 0.0)
        assertEquals(120, DuifenePolicy.MAX_LISTEN_MINUTES)
        assertTrue(settings.pollMinS > DuifenePolicy.NUMERIC_POLL_S)
    }

    @Test
    fun permissionDenialKeepsAppSessionAndHidesRecordId() {
        val (api, transport) = appApi()
        for (reply in listOf(
            DfReply(403, ""),
            DfReply(200, """{"success":false,"code":403,"message":"没有权限访问！"}"""),
            DfReply(200, """{"success":false,"code":"403","message":"没有权限访问！"}"""),
        )) {
            transport.calls.clear()
            transport.enqueue(reply.status, reply.body)
            val error = assertThrows(PermissionDenied::class.java) {
                api.fetchActiveCheckin("private-record-id")
            }
            assertTrue(error.message!!.contains("/CK/GetModelByInProgress"))
            assertFalse(error.message!!.contains("private-record-id"))
            assertTrue(api.isAuthenticated)
            assertEquals("test-access", api.token)
            assertEquals(1, transport.calls.size)
        }
    }

    @Test
    fun http503IsNotPermissionDenied() {
        val (api, transport) = appApi()
        transport.enqueue(503, "")
        val error = assertThrows(AppApiError::class.java) { api.fetchActiveCheckin("course") }
        assertFalse(error is PermissionDenied)
        assertTrue(api.isAuthenticated)
    }

    @Test
    fun studentDiscoveryKeepsLeadingZeroAndSkipsOldEndpoint() {
        val harness = discovery()
        harness.records(record())
        harness.checkin = detail()
        val found = harness.api.fetchActiveActivities(course())
        assertEquals(1, found.size)
        assertEquals("0073", found[0].code)
        assertEquals("self", found[0].studentId)
        assertTrue(found[0].hasValidTimes)
        val url = harness.transport.calls.single().url
        assertTrue(url.contains("/CK/GetStudentInLogList/class/self"))
        assertFalse(url.contains("GetModelByInProgress"))
    }

    @Test
    fun unknownCourseAndForeignStudentDoNotQueryFurther() {
        val harness = discovery()
        assertThrows(PermissionDenied::class.java) {
            harness.api.fetchActiveActivities(Course("other", "class", "其他"))
        }
        assertEquals(0, harness.transport.calls.size)
        harness.records(record(studentId = "someone-else"))
        assertThrows(PermissionDenied::class.java) {
            harness.api.fetchActiveActivities(course())
        }
        assertEquals(0, harness.checkinCalls)
    }

    @Test
    fun nullStudentAbsenceUsesCurrentStudentAndSignedNullIsSkipped() {
        val absence = discovery()
        absence.records(record(studentNull = true))
        absence.checkin = detail()
        assertEquals("self", absence.api.fetchActiveActivities(course()).single().studentId)

        val signed = discovery()
        signed.records(record(studentNull = true, statusId = "1", checkInStatus = "1", checkInDate = "2026/11/11 08:00:00"))
        assertTrue(signed.api.fetchActiveActivities(course()).isEmpty())
        assertEquals(0, signed.checkinCalls)

        val inconsistent = discovery()
        inconsistent.records(record(studentNull = true, statusId = "1", checkInStatus = "1", checkInDate = null))
        assertThrows(PermissionDenied::class.java) {
            inconsistent.api.fetchActiveActivities(course())
        }
    }

    @Test
    fun badTimeDeletedAndForeignClassAreNotSubmitted() {
        val bad = discovery()
        bad.records(record())
        bad.checkin = detail(valid = "invalid")
        assertThrows(AppApiError::class.java) { bad.api.fetchActiveActivities(course()) }

        val deleted = discovery()
        deleted.records(record())
        deleted.checkin = detail(deleted = true)
        assertTrue(deleted.api.fetchActiveActivities(course()).isEmpty())

        val otherClass = discovery()
        otherClass.records(record())
        otherClass.checkin = detail(classId = "other")
        assertThrows(PermissionDenied::class.java) { otherClass.api.fetchActiveActivities(course()) }
    }

    @Test
    fun numericMaterialKeepsLeadingZeroAndDoesNotPadIntegers() {
        val kept = discovery()
        kept.records(record())
        kept.checkin = detail(code = "")
        kept.web.numeric = mapOf("101" to classRow(code = "0073"))
        assertEquals("0073", kept.api.fetchActiveActivities(course()).single().code)
        assertEquals(setOf("101"), kept.web.numericCalls.single())

        val integer = discovery()
        integer.records(record())
        integer.checkin = detail(code = "")
        integer.web.numeric = mapOf("101" to classRow(codeNumber = 73))
        assertEquals("", integer.api.fetchActiveActivities(course()).single().code)
    }

    @Test
    fun numericRefreshDoesNotRepeatAppQueries() {
        val harness = discovery()
        harness.records(record())
        harness.checkin = detail(code = "")
        val found = harness.api.fetchActiveActivities(course(), includeNumericMaterials = false)
        assertEquals(0, harness.web.numericCalls.size)
        harness.transport.calls.clear()
        harness.checkinCalls = 0
        harness.api.refreshNumericMaterials(course(), found)
        harness.api.refreshNumericMaterials(course(), found)
        assertEquals(2, harness.web.numericCalls.size)
        assertEquals(0, harness.transport.calls.size)
        assertEquals(0, harness.checkinCalls)
    }

    @Test
    fun qrMaterialOnlyWhenSingleActivityAndSingleClass() {
        val one = discovery()
        one.records(record())
        one.checkin = detail(type = "2")
        one.web.token = "A".repeat(48)
        assertEquals("A".repeat(48), one.api.fetchActiveActivities(course()).single().qrToken)
        assertEquals(1, one.web.tokenCalls)

        val many = discovery()
        many.records(record(), record(id = "102"))
        many.checkinFor = { id -> detail(id = id, type = "2") }
        many.web.token = "B".repeat(48)
        assertEquals(2, many.api.fetchActiveActivities(course()).size)
        assertEquals(0, many.web.tokenCalls)

        val classes = discovery()
        classes.api.rememberCourses(listOf(course(), Course("course", "second", "课程")))
        classes.records(record())
        classes.checkin = detail(type = "2")
        classes.api.fetchActiveActivities(course())
        assertEquals(0, classes.web.tokenCalls)
    }

    @Test
    fun webFailureDoesNotClearAppSession() {
        val harness = discovery()
        harness.records(record())
        harness.checkin = detail(type = "2")
        harness.web.tokenError = WebSessionError("学生网页登录失败")
        assertThrows(WebSessionError::class.java) { harness.api.fetchActiveActivities(course()) }
        assertTrue(harness.api.appAuthenticated)
    }

    @Test
    fun savedPasswordFromAnotherAccountIsNotUsed() {
        val harness = discovery(saved = WebCredential("other", "test"))
        assertNull(harness.api.currentWebCredential())
    }

    @Test
    fun processorSubmitsLeadingZeroAndSkipsInvalidMaterial() {
        val board = FakeBoard()
        val processor = ActivityProcessor(DuifeneSettings(), board, {})
        assertEquals(Outcome.SIGNED, processor.process(sample(), course()))
        assertEquals(listOf("0073"), board.signedCodes)
        val again = ActivityProcessor(DuifeneSettings(), board, {})
        board.signedCodes.clear()
        board.headcountCalls = 0
        for (code in listOf("", "123", "１２３４", "abcd")) {
            assertEquals(Outcome.DEFERRED, again.process(sample(code = code), course()))
        }
        assertEquals(0, board.headcountCalls)
        assertTrue(board.signedCodes.isEmpty())
    }

    @Test
    fun unknownTimeAndIdentityMismatchDoNotSubmit() {
        val board = FakeBoard()
        val processor = ActivityProcessor(DuifeneSettings(), board, {})
        assertEquals(Outcome.DEFERRED, processor.process(sample().copy(validDate = "bad"), course()))
        assertEquals(0, board.headcountCalls)
        board.identity = AppIdentity("other", "other-student", "", "2")
        assertThrows(AuthError::class.java) { processor.process(sample(), course()) }
        assertTrue(board.signedCodes.isEmpty())
    }

    @Test
    fun headcountCacheExpiresAndDoesNotCrossActivities() {
        val board = FakeBoard()
        board.headcountQueue.add(0 to 10)
        board.headcountQueue.add(5 to 10)
        val clock = ManualClock(wallMs = System.currentTimeMillis())
        val processor = ActivityProcessor(DuifeneSettings(), board, {}, clock)
        assertEquals(Outcome.DEFERRED, processor.process(sample(), course()))
        clock.monoMs = 200
        assertEquals(Outcome.DEFERRED, processor.process(sample(), course()))
        clock.monoMs = 1_100
        assertEquals(Outcome.SIGNED, processor.process(sample(), course()))
        assertEquals(2, board.headcountCalls)

        val isolated = ActivityProcessor(DuifeneSettings(), board, {}, ManualClock(wallMs = System.currentTimeMillis()))
        board.headcountQueue.clear()
        board.headcountCalls = 0
        board.signResult = "人数不足"
        isolated.process(sample(), course())
        isolated.process(sample(id = "102"), course())
        assertEquals(2, board.headcountCalls)
    }

    @Test
    fun timeoutBecomesPendingAndDoesNotResubmitUntilRecheckSaysAbsent() {
        val board = FakeBoard()
        board.signError = UnconfirmedSubmit("超时")
        val processor = ActivityProcessor(DuifeneSettings(), board, {})
        assertEquals(Outcome.PENDING, processor.process(sample(), course()))
        assertEquals(1, board.signedCodes.size)
        board.signError = null
        board.confirm = null
        assertEquals(Outcome.PENDING, processor.process(sample(), course()))
        assertEquals(1, board.signedCodes.size)
        board.confirm = false
        board.signResult = "签到成功"
        assertEquals(Outcome.SIGNED, processor.process(sample(), course()))
        assertEquals(2, board.signedCodes.size)
    }

    @Test
    fun stoppedGenerationDoesNotStartAnotherSubmit() {
        val board = FakeBoard()
        board.allowSubmit = { false }
        val processor = ActivityProcessor(DuifeneSettings(), board, {})
        assertEquals(Outcome.DISCARDED, processor.process(sample(), course()))
        assertTrue(board.signedCodes.isEmpty())
    }

    @Test
    fun missingLocationDoesNotPretendSuccess() {
        val board = FakeBoard()
        board.locationFix = { null }
        val processor = ActivityProcessor(DuifeneSettings(), board, {})
        val activity = sample(type = "3", code = "")
        assertEquals(Outcome.DEFERRED, processor.process(activity, course()))
        assertEquals(0, board.locationSubmits)
        assertEquals("waiting_material", processor.statusPhase)
    }

    @Test
    fun fastNumericPollDoesNotRediscoverAndSubmitsOnce() {
        val source = FakeSource()
        val board = FakeBoard()
        val processor = ActivityProcessor(DuifeneSettings(), board, {})
        val logs = mutableListOf<String>()
        val watcher = Watcher(DuifeneSettings(), source, processor, logs::add)
        watcher.course = course()
        watcher.inMonitorWindow = { true }
        val missing = sample(code = "")
        source.activities = { listOf(missing) }
        source.refresh = { listOf(sample()) }
        watcher.tick()
        watcher.numericTick()
        watcher.numericTick()
        assertEquals(1, source.fetchCount)
        assertEquals(listOf("0073"), board.signedCodes)
        assertTrue(watcher.pendingNumeric.isEmpty())
    }

    @Test
    fun lastFiveSecondsPreferMaterialAndBackoffCaps() {
        val source = FakeSource()
        val board = FakeBoard()
        val processor = ActivityProcessor(DuifeneSettings(), board, {})
        val watcher = Watcher(DuifeneSettings(pollMinS = 3.0, pollMaxS = 3.0), source, processor, {})
        watcher.course = course()
        watcher.inMonitorWindow = { true }
        val soon = sample(code = "").copy(
            validDate = ZonedDateTime.now(SHANGHAI).plusSeconds(3).format(SLASH),
        )
        watcher.setPendingNumeric(listOf(soon))
        source.refresh = { throw AuthError("停止") }
        watcher.runLoop()
        assertEquals(0, source.fetchCount)

        val retry = Watcher(DuifeneSettings(), source, processor, { })
        retry.course = course()
        retry.inMonitorWindow = { true }
        retry.setPendingNumeric(listOf(sample(code = "")))
        source.refresh = { throw AppApiError("网络失败") }
        for (expected in listOf(0.4, 0.8, 1.6, 2.0, 2.0)) {
            retry.numericTick()
            assertEquals(expected, retry.numericPollDelay(), 0.001)
        }
        retry.numericFailures = 10_000
        assertEquals(2.0, retry.numericPollDelay(), 0.001)
        retry.setPendingNumeric(emptyList())
        assertEquals(0.2, retry.numericPollDelay(), 0.001)
    }

    @Test
    fun emptyDiscoveryKeepsLiveCandidateAndDropsForeignOne() {
        val source = FakeSource()
        val watcher = Watcher(DuifeneSettings(), source, ActivityProcessor(DuifeneSettings(), FakeBoard(), {}), {})
        watcher.course = course()
        watcher.inMonitorWindow = { true }
        source.activities = { emptyList() }
        watcher.setPendingNumeric(listOf(sample(code = "")))
        watcher.tick()
        assertTrue(watcher.pendingNumeric.containsKey("101"))
        watcher.setPendingNumeric(listOf(sample(code = "").copy(studentId = "other")))
        watcher.tick()
        assertTrue(watcher.pendingNumeric.isEmpty())
    }

    @Test
    fun discoveryIntervalStaysIndependentOfNumericDeadline() {
        val settings = DuifeneSettings(pollMinS = 4.0, pollMaxS = 4.0)
        val watcher = Watcher(settings, FakeSource(), ActivityProcessor(settings, FakeBoard(), {}), {})
        assertEquals(4.0, watcher.pollDelay(), 0.0)
        watcher.numericDeadline = ZonedDateTime.now(SHANGHAI).plusSeconds(3)
        assertEquals(4.0, watcher.pollDelay(), 0.0)
    }

    @Test
    fun runLoopRetriesTransientFailureAndStopsOnPermission() {
        val source = FakeSource()
        var calls = 0
        val watcherHolder = arrayOfNulls<Watcher>(1)
        source.activities = {
            calls += 1
            if (calls == 1) throw AppApiError("网络请求失败")
            emptyList()
        }
        val logs = mutableListOf<String>()
        val watcher = Watcher(
            DuifeneSettings(),
            source,
            ActivityProcessor(DuifeneSettings(), FakeBoard(), {}),
            logs::add,
        ) {
            if (calls >= 2) watcherHolder[0]?.requestStop()
        }
        watcherHolder[0] = watcher
        watcher.course = course()
        watcher.inMonitorWindow = { true }
        watcher.runLoop()
        assertEquals(2, calls)
        assertTrue(logs.any { "后重试" in it })

        val denied = FakeSource()
        denied.activities = { throw PermissionDenied("无访问权限（403）") }
        val deniedLogs = mutableListOf<String>()
        val stopped = Watcher(DuifeneSettings(), denied, ActivityProcessor(DuifeneSettings(), FakeBoard(), {}), deniedLogs::add)
        stopped.course = course()
        stopped.inMonitorWindow = { true }
        stopped.runLoop()
        assertEquals(1, denied.fetchCount)
        assertTrue(deniedLogs.any { "监听已停止" in it && "403" in it })
        assertFalse(deniedLogs.any { "后重试" in it })
    }

    @Test
    fun scheduleSplitsDiscoveryAndMaterialWaits() {
        val clock = ManualClock(wallMs = System.currentTimeMillis())
        val source = FakeSource()
        val board = FakeBoard()
        val processor = ActivityProcessor(DuifeneSettings(), board, {}, clock)
        var mono = 0L
        val holder = arrayOfNulls<Watcher>(1)
        source.activities = { listOf(sample(code = "")) }
        source.refresh = { listOf(sample(code = "")) }
        val loop = Watcher(
            DuifeneSettings(pollMinS = 3.0, pollMaxS = 3.0),
            source,
            processor,
            {},
            clock,
        ) { seconds ->
            mono += (seconds * 1000.0).toLong().coerceAtLeast(1L)
            clock.monoMs = mono
            if (mono >= 6_100L) holder[0]?.requestStop()
        }
        holder[0] = loop
        loop.course = course()
        loop.inMonitorWindow = { true }
        loop.runLoop()
        assertEquals(3, source.fetchCount)
        assertTrue(source.refreshCount in 25..35)
        assertEquals(0, board.signedCodes.size)
    }

    @Test
    fun officialQrTextIsAcceptedAndForeignShapesAreRejected() {
        val token = "A".repeat(48)
        val official = "https://www.duifene.com/G.aspx?OnlyId=2&e=$token"
        assertEquals(official, decodeMatrix(official))
        assertEquals(token, acceptQrText(decodeMatrix(official)))
        val rejected = listOf(
            "https://example.com/G.aspx?OnlyId=2&e=$token",
            "https://www.duifene.com/G.aspx?OnlyId=1&e=$token",
            "https://www.duifene.com/G.aspx?OnlyId=2&e=$token&e=$token",
            "https://www.duifene.com/G.aspx?OnlyId=2&e=bad",
            "https://www.duifene.com/G.aspx?OnlyId=2&e=$token#other",
        )
        for (url in rejected) {
            assertThrows(AppApiError::class.java) {
                decodeQrToken("YQ==", QrImageDecoder { listOf(url) })
            }
        }
        var called = false
        assertThrows(AppApiError::class.java) {
            decodeQrToken("A".repeat(2_000_001), QrImageDecoder { called = true; emptyList() })
        }
        assertFalse(called)
        assertThrows(AppApiError::class.java) { decodeQrToken("not base64!", QrImageDecoder { emptyList() }) }
        assertThrows(AppApiError::class.java) { checkImageBounds(3_000, 2_000) }
    }

    @Test
    fun webLoginRecoversOnceAndKeepsLeadingZeroRow() {
        val token = "A".repeat(48)
        val transport = ScriptedTransport()
        val web = StudentWebApi(DuifeneSettings(), transport, { WebCredential("student", "test&password") }, fixedDecoder(token))
        transport.enqueue(200, "home")
        transport.enqueue(200, """{"msgbox":"登录成功"}""")
        transport.enqueue(200, """{"msg":"1","data":"aaaa"}""")
        assertEquals(token, web.fetchToken("course"))
        val login = parseForm(transport.calls[1].body.orEmpty())
        assertEquals("loginmb", login["action"])
        assertEquals("student", login["loginname"])
        assertEquals("test&password", login["password"])
        assertEquals("getcodeimage", parseForm(transport.calls.last().body.orEmpty())["action"])
        assertTrue(transport.calls.all { !it.followRedirects })
    }

    @Test
    fun webSessionPermissionAndNumericRows() {
        val token = "B".repeat(48)
        val transport = ScriptedTransport()
        val web = StudentWebApi(DuifeneSettings(), transport, { WebCredential("student", "test") }, fixedDecoder(token))
        transport.enqueue(200, "home")
        transport.enqueue(200, """{"msgbox":"登录成功"}""")
        transport.enqueue(401, "")
        transport.enqueue(200, "home")
        transport.enqueue(200, """{"msgbox":"登录成功"}""")
        transport.enqueue(200, """{"msg":"1","data":"zzzz"}""")
        assertEquals(token, web.fetchToken("course"))
        assertEquals(6, transport.calls.size)

        val denied = ScriptedTransport()
        val deniedWeb = StudentWebApi(DuifeneSettings(), denied, { WebCredential("student", "test") }, fixedDecoder(token))
        denied.enqueue(200, "home")
        denied.enqueue(200, """{"msgbox":"登录成功"}""")
        denied.enqueue(403, "")
        assertThrows(PermissionDenied::class.java) { deniedWeb.fetchToken("course") }
        assertEquals(3, denied.calls.size)

        val missing = StudentWebApi(DuifeneSettings(), ScriptedTransport(), { null }, fixedDecoder(token))
        assertThrows(WebSessionError::class.java) { missing.fetchToken("course") }

        val rows = ScriptedTransport()
        val numeric = StudentWebApi(DuifeneSettings(), rows, { WebCredential("student", "test") }, fixedDecoder(token))
        rows.enqueue(200, "home")
        rows.enqueue(200, """{"msgbox":"登录成功"}""")
        rows.enqueue(200, """{"rows":[{"ID":"100","TClassID":"class","CheckInCode":"1111"},{"ID":"101","TClassID":"class","CheckInCode":"0073"}]}""")
        val matched = numeric.fetchNumericMaterials("class", setOf("101"))
        assertEquals(setOf("101"), matched.keys)
        assertEquals("0073", matched.getValue("101").getString("CheckInCode"))
        assertEquals("gettclasscheckinlistbypage", parseForm(rows.calls.last().body.orEmpty())["action"])

        val foreign = ScriptedTransport()
        val foreignWeb = StudentWebApi(DuifeneSettings(), foreign, { WebCredential("student", "test") }, fixedDecoder(token))
        foreign.enqueue(200, "home")
        foreign.enqueue(200, """{"msgbox":"登录成功"}""")
        foreign.enqueue(200, """{"rows":[{"ID":"101","TClassID":"other","CheckInCode":"0073"}]}""")
        assertThrows(PermissionDenied::class.java) { foreignWeb.fetchNumericMaterials("class", setOf("101")) }

        val duplicate = ScriptedTransport()
        val duplicateWeb = StudentWebApi(DuifeneSettings(), duplicate, { WebCredential("student", "test") }, fixedDecoder(token))
        duplicate.enqueue(200, "home")
        duplicate.enqueue(200, """{"msgbox":"登录成功"}""")
        duplicate.enqueue(200, """{"rows":[{"ID":"101","TClassID":"class","CheckInCode":"0073"},{"ID":"101","TClassID":"class","CheckInCode":"0073"}]}""")
        assertThrows(AppApiError::class.java) { duplicateWeb.fetchNumericMaterials("class", setOf("101")) }
        assertTrue(StudentWebApi(DuifeneSettings(), ScriptedTransport(), { WebCredential("student", "test") }, fixedDecoder(token)).fetchNumericMaterials("class", emptySet()).isEmpty())
    }

    @Test
    fun snapshotDoesNotCarrySecrets() {
        val view = DuifeneView(loggedIn = true, accountMasked = maskAccount("13800138000"), message = "已登录")
        val json = view.toJson()
        val keys = json.keys().asSequence().toSet()
        assertFalse(keys.any { it.equals("password", true) || it.equals("token", true) || it.equals("cookie", true) })
        assertFalse(json.toString().contains("13800138000"))
        assertTrue(json.getString("accountMasked").contains("****"))
        assertEquals("none", json.getString("activityPhase"))
    }

    private fun appApi(): Pair<DfAppApi, ScriptedTransport> {
        val transport = ScriptedTransport()
        val api = DfAppApi(transport, DuifeneSettings())
        assertTrue(api.storeLoginPayload(loginPayload(), initialLogin = true))
        return api to transport
    }

    private fun discovery(saved: WebCredential? = null): DiscoveryHarness {
        val transport = ScriptedTransport()
        val app = DfAppApi(transport, DuifeneSettings())
        assertTrue(app.storeLoginPayload(loginPayload(), initialLogin = true))
        val web = FakeWeb()
        val api = DuifeneApi(app, web, DuifeneSettings(), RateLimiter(DuifeneSettings()), credentials = { saved })
        api.rememberCourses(listOf(course()))
        return DiscoveryHarness(api, app, transport, web)
    }

    private fun loginPayload(): JSONObject = JSONObject(
        """{"success":true,"code":200,"data":{"token":"test-access","refreshToken":"test-refresh","userInfo":{"userId":"self","userLogin":"student","userName":"测试","userTypeID":"2"}}}"""
    )

    private fun course() = Course("course", "class", "课程")

    private fun sample(
        code: String = "0073",
        id: String = "101",
        type: String = "1",
    ): AppCheckIn {
        val now = ZonedDateTime.now(SHANGHAI)
        return AppCheckIn(
            activityId = id,
            checkType = type,
            code = code,
            qrToken = "",
            roomId = "0",
            classId = "class",
            createDate = now.minusSeconds(60).format(SLASH),
            validDate = now.plusMinutes(5).format(SLASH),
            checkInStatus = "",
            statusId = "2",
            statusName = "缺勤",
            studentId = "self",
        )
    }
}

private val SLASH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

private class DiscoveryHarness(
    val api: DuifeneApi,
    val app: DfAppApi,
    val transport: ScriptedTransport,
    val web: FakeWeb,
) {
    var checkin: AppCheckIn = detail()
    var checkinFor: ((String) -> AppCheckIn)? = null
    var checkinCalls: Int = 0

    init {
        app.checkinOverride = { id ->
            checkinCalls += 1
            checkinFor?.invoke(id) ?: checkin
        }
    }

    fun records(vararg rows: JSONObject) {
        transport.enqueue(200, JSONObject().put("success", true).put("data", JSONArray().apply {
            rows.forEach { put(it) }
        }).toString())
    }
}

private fun record(
    id: String = "101",
    studentId: String? = "self",
    studentNull: Boolean = false,
    statusId: String = "2",
    checkInStatus: String = "",
    checkInDate: String? = null,
): JSONObject {
    val row = JSONObject()
    row.put("ID", id)
    if (studentNull) row.put("StudentID", JSONObject.NULL) else row.put("StudentID", studentId)
    row.put("StatusID", statusId)
    row.put("StatusName", if (statusId == "1") "出勤" else "缺勤")
    row.put("CheckInType", "1")
    if (checkInStatus.isEmpty()) row.put("CheckInStatus", "") else row.put("CheckInStatus", checkInStatus)
    if (checkInDate == null) row.put("CheckInDate", JSONObject.NULL) else row.put("CheckInDate", checkInDate)
    row.put("CreaterDate", ZonedDateTime.now(SHANGHAI).minusSeconds(60).format(SLASH))
    return row
}

private fun detail(
    id: String = "101",
    code: String = "0073",
    type: String = "1",
    classId: String = "class",
    valid: String? = null,
    deleted: Boolean = false,
): AppCheckIn {
    val now = ZonedDateTime.now(SHANGHAI)
    return AppCheckIn(
        activityId = id,
        checkType = type,
        code = code,
        qrToken = "",
        roomId = "0",
        classId = classId,
        createDate = now.minusSeconds(60).format(SLASH),
        validDate = valid ?: now.plusMinutes(5).format(SLASH),
        checkInStatus = "",
        statusId = "2",
        statusName = "缺勤",
        studentId = "self",
        isDeleted = deleted,
    )
}

private fun classRow(code: String? = null, codeNumber: Int? = null): JSONObject {
    val now = ZonedDateTime.now(SHANGHAI)
    val row = JSONObject()
        .put("ID", "101")
        .put("TClassID", "class")
        .put("CheckInType", "1")
        .put("CreaterDate", now.minusSeconds(60).format(SLASH))
        .put("ValidDate", now.plusMinutes(5).format(SLASH))
        .put("IsDel", "0")
    if (code != null) row.put("CheckInCode", code)
    if (codeNumber != null) row.put("CheckInCode", codeNumber)
    return row
}

private class FakeWeb : WebMaterials {
    val numericCalls = mutableListOf<Set<String>>()
    var numeric: Map<String, JSONObject> = emptyMap()
    var token: String = ""
    var tokenCalls = 0
    var tokenError: Exception? = null
    override fun clear() = Unit
    override fun fetchToken(courseId: String): String {
        tokenCalls += 1
        tokenError?.let { throw it }
        return token
    }
    override fun fetchNumericMaterials(classId: String, activityIds: Set<String>): Map<String, JSONObject> {
        numericCalls += activityIds
        return numeric
    }
}

private class FakeSource : ActivitySource {
    override var appAuthenticated: Boolean = true
    override var identity: AppIdentity? = AppIdentity("student", "self", "", "2")
    override val limiter: RateLimiter = RateLimiter(DuifeneSettings())
    var fetchCount = 0
    var refreshCount = 0
    var activities: () -> List<AppCheckIn> = { emptyList() }
    var refresh: (List<AppCheckIn>) -> List<AppCheckIn> = { it }
    var stop = false
    override fun fetchActiveActivities(course: Course, includeNumericMaterials: Boolean): List<AppCheckIn> {
        fetchCount += 1
        return activities()
    }
    override fun refreshNumericMaterials(course: Course, activities: List<AppCheckIn>): List<AppCheckIn> {
        refreshCount += 1
        return refresh(activities)
    }
}

private class FakeBoard : SignBoard {
    override var identity: AppIdentity? = AppIdentity("student", "self", "", "2")
    override val limiter: RateLimiter = RateLimiter(DuifeneSettings())
    override var allowSubmit: () -> Boolean = { true }
    override var sameSession: () -> Boolean = { true }
    override var locationFix: () -> GeoPoint? = { GeoPoint(22.6, 113.9) }
    val headcountQueue = ArrayDeque<Pair<Int, Int>>()
    var headcountCalls = 0
    val signedCodes = mutableListOf<String>()
    var signResult = "签到成功"
    var signError: Exception? = null
    var confirm: Boolean? = false
    var locationSubmits = 0
    override fun fetchHeadcount(activityId: String): Pair<Int, Int> {
        headcountCalls += 1
        if (headcountQueue.isEmpty()) return 1 to 1
        return headcountQueue.removeFirst()
    }
    override fun signByCode(code: String): String {
        signedCodes += code
        signError?.let { throw it }
        return signResult
    }
    override fun signByQr(token: String): String = signResult
    override fun signByLocation(longitude: Double, latitude: Double): String {
        locationSubmits += 1
        return signResult
    }
    override fun confirmOwnStatus(classId: String, activityId: String): Boolean? = confirm
}

private fun fixedDecoder(token: String) = QrImageDecoder {
    listOf("https://www.duifene.com/G.aspx?OnlyId=2&e=$token")
}

private fun decodeMatrix(url: String): String {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 180, 180)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
    val reader = MultiFormatReader()
    reader.setHints(
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
    )
    return reader.decode(BinaryBitmap(HybridBinarizer(source))).text
}

private fun parseForm(body: String): Map<String, String> =
    body.split("&").filter { it.isNotBlank() }.associate { part ->
        val eq = part.indexOf("=")
        val key = java.net.URLDecoder.decode(part.substring(0, eq), Charsets.UTF_8)
        val value = java.net.URLDecoder.decode(part.substring(eq + 1), Charsets.UTF_8)
        key to value
    }
