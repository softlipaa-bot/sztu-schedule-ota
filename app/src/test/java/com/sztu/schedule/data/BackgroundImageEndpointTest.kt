package com.sztu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 受限背景图端点白名单（审查增量 J29-01）。
 *
 * 这组用例锁定的是「不得扩成任意文件读取」这条硬约束：只有
 * `/bg/schedule_bg.jpg` 这一个文件名可以通过，任何上跳、子目录、绝对路径、
 * 编码变形、大小写变体、附加查询串都不行。
 */
class BackgroundImageEndpointTest {

    @Test
    fun allowsOnlyTheWhitelistedFileName() {
        assertTrue(BackgroundImageEndpoint.isAllowedPath("schedule_bg.jpg"))
        // 不同 androidx.webkit 版本去掉前缀后是否保留前导斜杠有差异，两种都放行；
        // 两者指向的都是同一个固定文件名，不构成路径放宽。
        assertTrue(BackgroundImageEndpoint.isAllowedPath("/schedule_bg.jpg"))
    }

    @Test
    fun rejectsEmptyAndMalformedPaths() {
        val bad = listOf(
            null,
            "",
            " ",
            ".",
            "..",
            "/",
            "schedule_bg.jpg ",
            " schedule_bg.jpg",
            "\u0000schedule_bg.jpg",
        )
        for (path in bad) {
            assertFalse("should reject: $path", BackgroundImageEndpoint.isAllowedPath(path))
        }
    }

    @Test
    fun rejectsPathTraversal() {
        val bad = listOf(
            "../schedule_bg.jpg",
            "../../schedule_bg.jpg",
            "/../schedule_bg.jpg",
            "..%2Fschedule_bg.jpg",
            "%2e%2e/schedule_bg.jpg",
            "%2e%2e%2fschedule_bg.jpg",
            "..\\schedule_bg.jpg",
            "./schedule_bg.jpg",
            "sub/schedule_bg.jpg",
            "sub/../schedule_bg.jpg",
        )
        for (path in bad) {
            assertFalse("should reject: $path", BackgroundImageEndpoint.isAllowedPath(path))
        }
    }

    @Test
    fun rejectsOtherFilesInTheSameDirectory() {
        // data 目录里的其它文件（偏好/缓存/库）都必须拿不到
        val bad = listOf(
            "databases/sztu_schedule.db",
            "sztu_schedule.xml",
            "duifenyi.ini",
            "schedule.json",
            "schedule.json.bak",
            "schedule_bg.jpg.bak",
            "schedule_bg.jpg/../duifenyi.ini",
        )
        for (path in bad) {
            assertFalse("should reject: $path", BackgroundImageEndpoint.isAllowedPath(path))
        }
    }

    @Test
    fun rejectsSuffixAndCaseVariants() {
        val bad = listOf(
            "SCHEDULE_BG.JPG",
            "Schedule_Bg.jpg",
            "schedule_bg.jpg.bak",
            "schedule_bg.jp",
            "schedule_bg.jpgx",
            "xschedule_bg.jpg",
            "schedule_bg.jpg?v=1",
            "schedule_bg.jpg%3Fv=1",
            "schedule_bg.jpg;.png",
            "schedule_bg.jpg\u0000.png",
        )
        for (path in bad) {
            assertFalse("should reject: $path", BackgroundImageEndpoint.isAllowedPath(path))
        }
    }

    @Test
    fun versionedUrlIsShortAndCarriesTheStamp() {
        assertEquals("", BackgroundImageEndpoint.versionedUrl(0L))
        assertEquals("", BackgroundImageEndpoint.versionedUrl(-5L))
        assertEquals(
            "/bg/schedule_bg.jpg?v=12345",
            BackgroundImageEndpoint.versionedUrl(12345L),
        )
        // 版本号必须真的出现在 URL 里，否则换图后 WebView 会一直用旧缓存
        assertTrue(BackgroundImageEndpoint.versionedUrl(999L).endsWith("v=999"))
    }

    @Test
    fun responseMetadataIsImmutableJpeg() {
        assertEquals("image/jpeg", BackgroundImageEndpoint.contentType())
        val cc = BackgroundImageEndpoint.cacheControl()
        assertTrue(cc.contains("max-age="))
        assertTrue(cc.contains("immutable"))
    }

    @Test
    fun prefixDoesNotCollideWithTheAssetLoaderPrefix() {
        // MainActivity 用 /assets/ 挂静态资源；背景前缀不能与它重叠
        assertEquals("/bg/", BackgroundImageEndpoint.PREFIX)
        assertFalse(BackgroundImageEndpoint.PREFIX.startsWith("/assets/"))
        assertFalse("/assets/".startsWith(BackgroundImageEndpoint.PREFIX))
    }
}
