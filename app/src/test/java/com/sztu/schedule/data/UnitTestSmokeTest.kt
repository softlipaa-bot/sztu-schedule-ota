package com.sztu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 对照实验：确认 Gradle 单元测试链路本身可用（与业务无关，可保留为冒烟测试）。 */
class UnitTestSmokeTest {
    @Test
    fun junitRuntimeIsWired() {
        assertEquals(2, 1 + 1)
    }
}
