package com.sztu.schedule.duifene

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DuifeneFixTest {
    @Test(timeout = 8000)
    fun stopCancelsOnlyThatScopeAndRefreshStillWorks() {
        val server = ServerSocket(0)
        val firstHeaders = CountDownLatch(1)
        val serverDone = CountDownLatch(1)
        Thread {
            val first = server.accept()
            readHeaders(first)
            firstHeaders.countDown()
            runCatching { first.getInputStream().read() }
            runCatching { first.close() }
            val second = server.accept()
            readHeaders(second)
            writeHttp(second, "ok")
            second.close()
            serverDone.countDown()
        }.apply { isDaemon = true; start() }

        val transport = AndroidDfTransport(DuifeneSettings(requestTimeoutMs = 2000))
        val watch = transport.newScope()
        val url = "http://127.0.0.1:${server.localPort}/ping"
        val failure = AtomicReference<TransportException>()
        val worker = Thread {
            try {
                transport.exchange(DfCall("GET", url), watch)
            } catch (error: TransportException) {
                failure.set(error)
            }
        }
        worker.isDaemon = true
        worker.start()
        assertTrue(firstHeaders.await(3, TimeUnit.SECONDS))
        watch.cancel()
        worker.join(3000)
        val error = failure.get()
        assertNotNull(error)
        assertFalse(error!!.uncertain)
        assertFalse(error.sent)
        val refreshed = transport.exchange(DfCall("GET", url), transport.idleScope)
        assertEquals(200, refreshed.status)
        assertEquals("ok", refreshed.body)
        assertTrue(serverDone.await(3, TimeUnit.SECONDS))
        server.close()
    }

    @Test(timeout = 8000)
    fun cancellingWatchDoesNotDropAnIdleConnection() {
        val server = ServerSocket(0)
        val idleArrived = CountDownLatch(1)
        val releaseIdle = CountDownLatch(1)
        Thread {
            val socket = server.accept()
            readHeaders(socket)
            idleArrived.countDown()
            assertTrue(releaseIdle.await(3, TimeUnit.SECONDS))
            writeHttp(socket, "kept")
            socket.close()
        }.apply { isDaemon = true; start() }
        val transport = AndroidDfTransport(DuifeneSettings(requestTimeoutMs = 3000))
        val watch = transport.newScope()
        watch.cancel()
        val url = "http://127.0.0.1:${server.localPort}/idle"
        val body = AtomicReference<String>()
        val worker = Thread {
            body.set(transport.exchange(DfCall("GET", url), transport.idleScope).body)
        }
        worker.isDaemon = true
        worker.start()
        assertTrue(idleArrived.await(3, TimeUnit.SECONDS))
        watch.cancel()
        releaseIdle.countDown()
        worker.join(3000)
        assertEquals("kept", body.get())
        server.close()
    }

    @Test(timeout = 8000)
    fun postedBodyThenResetIsUnconfirmedButUnsentCancelIsNot() {
        assertFalse(submissionUncertain("GET", sent = true, cancelled = false))
        assertFalse(submissionUncertain("POST", sent = false, cancelled = true))
        assertTrue(submissionUncertain("POST", sent = true, cancelled = false))
        assertTrue(submissionUncertain("POST", sent = true, cancelled = true))

        val server = ServerSocket(0)
        Thread {
            val socket = server.accept()
            readHeaders(socket)
            socket.close()
        }.apply { isDaemon = true; start() }
        val transport = AndroidDfTransport(DuifeneSettings(requestTimeoutMs = 2000))
        val error = try {
            transport.exchange(
                DfCall("POST", "http://127.0.0.1:${server.localPort}/sign", body = "{\"checkInCode\":\"0000\"}"),
                transport.idleScope,
            )
            null
        } catch (caught: TransportException) {
            caught
        }
        assertNotNull(error)
        assertTrue(error!!.sent)
        assertTrue(error.uncertain)
        val scope = transport.newScope()
        scope.cancel()
        val cancelled = try {
            transport.exchange(DfCall("POST", "https://dfapp.duifene.com/CK/StudentCheckIn", body = "{}"), scope)
            null
        } catch (caught: TransportException) {
            caught
        }
        assertNotNull(cancelled)
        assertFalse(cancelled!!.sent)
        assertFalse(cancelled.uncertain)
        server.close()
    }

    @Test
    fun webApiUsesThePasswordJustTypedInsteadOfTheSavedOne() {
        var stored: WebCredential? = WebCredential("student", "old-secret")
        val hub = CredentialHub(
            saved = { stored },
            persist = { account, password -> stored = WebCredential(account, password) },
            forget = { stored = null },
        )
        hub.accept("student", "fresh-secret", persistPassword = false)
        assertNull(stored)
        val transport = ScriptedTransport()
        val web = StudentWebApi(DuifeneSettings(), transport, { hub.current("student") }, QrImageDecoder { emptyList() })
        transport.enqueue(200, "home", emptyList())
        transport.enqueue(200, """{"msgbox":"登录成功"}""", emptyList())
        transport.enqueue(403, "", emptyList())
        try {
            web.fetchToken("course")
        } catch (_: PermissionDenied) {
        }
        val login = transport.calls[1].body.orEmpty()
        assertTrue(login.contains("fresh-secret"))
        assertFalse(login.contains("old-secret"))
        hub.clearSession()
        assertNull(hub.current("student"))
    }

    @Test
    fun unknownSubmitSurvivesRestartAndDoesNotSendAgain() {
        val board = object : SignBoard {
            override var identity: AppIdentity? = AppIdentity("student", "self", "", "2")
            override val limiter = RateLimiter(DuifeneSettings())
            override var allowSubmit: () -> Boolean = { true }
            override var sameSession: () -> Boolean = { true }
            override var locationFix: () -> GeoPoint? = { null }
            var signs = 0
            override fun fetchHeadcount(activityId: String) = 1 to 1
            override fun signByCode(code: String): String {
                signs += 1
                throw UnconfirmedSubmit("结果未知")
            }
            override fun signByQr(token: String) = ""
            override fun signByLocation(longitude: Double, latitude: Double) = ""
            override fun confirmOwnStatus(classId: String, activityId: String): Boolean? = null
        }
        val ledger = PendingLedger()
        val processor = ActivityProcessor(DuifeneSettings(), board, {}, ledger = ledger)
        val now = java.time.ZonedDateTime.now(SHANGHAI)
        val activity = AppCheckIn(
            activityId = "101",
            checkType = "1",
            code = "0073",
            qrToken = "",
            roomId = "0",
            classId = "class",
            createDate = now.minusSeconds(60).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")),
            validDate = now.plusMinutes(5).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")),
            checkInStatus = "",
            statusId = "2",
            statusName = "缺勤",
            studentId = "self",
        )
        assertEquals(Outcome.PENDING, processor.process(activity, Course("course", "class", "高等数学 · A班")))
        processor.reset()
        assertEquals(Outcome.PENDING, processor.process(activity, Course("course", "class", "高等数学 · A班")))
        assertEquals(1, board.signs)
        assertTrue(ledger.snapshot().isNotEmpty())
    }

    @Test
    fun sameSuccessOnTwoActivitiesStaysTwoAndClearDoesNotRefill() {
        val book = RecordBook()
        book.note("student", "a", "101", "success", "签到成功", "高等数学 · A班", 1)
        book.note("student", "b", "102", "success", "签到成功", "大学英语 · B班", 2)
        book.note("student", "a", "101", "success", "签到成功", "高等数学 · A班", 3)
        assertEquals(listOf("高等数学 · A班", "大学英语 · B班"), book.snapshot().map { it.courseLabel })
        assertEquals(2, book.snapshot().size)
        book.clear()
        assertTrue(book.snapshot().isEmpty())
        book.note("student", "a", "101", "success", "签到成功", "高等数学 · A班", 4)
        assertTrue(book.snapshot().isEmpty())
        book.note("student", "a", "101", "pending_confirm", "待确认", "高等数学 · A班", 5)
        assertEquals(1, book.snapshot().size)
    }

    @Test
    fun oldRunCannotStopTheReplacementService() {
        val gate = WatchRunGate()
        val binding = RunServiceBinding()
        val first = gate.tryStart()
        assertNotNull(first)
        assertTrue(gate.markRunning(first!!))
        binding.bind(first)
        assertNull(gate.tryStart())
        assertTrue(gate.finish(first, "stopped"))
        val second = gate.tryStart()
        assertNotNull(second)
        assertTrue(gate.markRunning(second!!))
        binding.bind(second)
        val stopped = AtomicBoolean(false)
        assertFalse(binding.stopIfBound(first) { stopped.set(true) })
        assertFalse(stopped.get())
        assertTrue(binding.stopIfBound(second) { stopped.set(true) })
        assertTrue(stopped.get())
        assertFalse(gate.finish(first, "stopped"))
    }

    @Test
    fun startReturnsBeforeAuthenticationFinishes() {
        val gate = WatchRunGate()
        val executor = Executors.newSingleThreadExecutor()
        val started = AsyncStart(executor, gate)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val result = started.request {
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            gate.markRunning(it)
        }
        assertEquals("starting", result)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertEquals("starting", gate.phase)
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (gate.phase != "running" && System.nanoTime() < deadline) Thread.yield()
        assertEquals("running", gate.phase)
        executor.shutdownNow()
    }

    @Test
    fun signTimeoutBecomesPendingThroughTheApiMapper() {
        val transport = object : DfTransport {
            override fun exchange(call: DfCall): DfReply {
                if (call.url.contains("StudentCheckIn")) {
                    throw TransportException("网络超时", uncertain = true, sent = true)
                }
                return DfReply(200, JSONObject().put("success", true).put("code", 200).put("data", JSONObject()).toString())
            }
        }
        val api = DfAppApi(transport, DuifeneSettings())
        assertTrue(
            api.storeLoginPayload(
                JSONObject(
                    """{"success":true,"code":200,"data":{"token":"test-access","refreshToken":"test-refresh","userInfo":{"userId":"self","userLogin":"student","userTypeID":"2"}}}"""
                ),
                initialLogin = true,
            )
        )
        try {
            api.signByCode("0073")
            throw AssertionError("应该进入结果未知")
        } catch (error: UnconfirmedSubmit) {
            assertTrue(error.message!!.contains("回查"))
        }
    }
}

private fun readHeaders(socket: java.net.Socket) {
    val reader = socket.getInputStream().bufferedReader()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) break
    }
}

private fun writeHttp(socket: java.net.Socket, body: String) {
    val bytes = body.toByteArray()
    val header = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
    socket.getOutputStream().write(header.toByteArray())
    socket.getOutputStream().write(bytes)
    socket.getOutputStream().flush()
}
