"""UI 交互验收（2.1.0 FlClash 改造）—— 直接跑 APK 内的真实前端资源。

与 tools/test-web-startup.py（首屏 6 场景回归）互补：本脚本只覆盖「改造后必须成立
的交互与语义」，全部断言都看**实际渲染结果/计算样式**，不检查实现语句。

覆盖范围
  1. 主题：顶部按钮每次点击都立即浅↔深切换；选择持久化；外观页选中态与真实模式一致；
     跟随系统只在 system 模式生效；刷新后保持。
  2. 导航：今日 / 课表 / 出勤 三个入口（无对分易考勤），隐藏面板 inert + aria-hidden（不可键盘访问）。
  3. 主卡状态语义：08:29 → 下一节、08:30 → 正在上课、09:55 整点 → 今日课程已结束。
  4. 动效：减少动画偏好下装饰动画关闭；页面隐藏时装饰动画暂停（不新增定时器）。
  5. 设置页：登录与同步 / 外观 / 选择背景 / 教学楼地图 / 校历起点 / 关于 全部可达。
  6. 宽屏：侧栏出现、Dock 不再存在于 DOM，右栏标题与主卡顶部对齐（不出现大断层）。

这不是 Android 真机验证：Chromium 里的 WebView 资源 + 假原生桥。
"""

import argparse
from datetime import datetime, timedelta, timezone
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
from pathlib import Path
import threading
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile

from playwright.sync_api import sync_playwright

CST = timezone(timedelta(hours=8))

# 与 test-web-startup.py 相同的真实课程时间（周六 08:30–09:55）
FIXTURE_COURSE = {
    "id": "ui-fixture",
    "name": "启动回归测试课程",
    "teacher": "测试教师",
    "place": "测试教室",
    "weeks": "1-18(周)[01-02节]",
    "weekday": 6,
    "startSection": 1,
    "endSection": 2,
    "startTime": "08:30",
    "endTime": "09:55",
    "sectionLabel": "第1-2节",
    "timeLabel": "08:30–09:55",
}

SETTINGS_PAGES = [
    ("登录与同步", "登录与同步"),
    ("外观", "外观"),
    ("选择背景", "选择背景"),
    ("教学楼地图", "教学楼地图"),
    ("校历起点", "校历起点"),
    ("关于", "关于"),
]


class AssetsHandler(BaseHTTPRequestHandler):
    def __init__(self, *args, archive, **kwargs):
        self.archive = archive
        super().__init__(*args, **kwargs)

    def do_GET(self):
        name = unquote(urlsplit(self.path).path).lstrip("/")
        if not name.startswith("assets/www/"):
            self.send_error(404)
            return
        try:
            data = self.archive.read(name)
        except KeyError:
            self.send_error(404)
            return
        mime = {".js": "text/javascript", ".css": "text/css"}.get(Path(name).suffix)
        self.send_response(200)
        self.send_header("Content-Type", mime or mimetypes.guess_type(name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_args):
        pass


def set_visibility(page, state):
    page.evaluate(
        """(state) => {
            Object.defineProperty(document, 'visibilityState', {
                configurable: true,
                get: () => state,
            });
            document.dispatchEvent(new Event('visibilitychange'));
        }""",
        state,
    )


def snapshot(*, session_state="SERVER_VERIFIED", courses=None, **extra):
    snap = {
        "courses": courses if courses is not None else [FIXTURE_COURSE],
        "term": "2026-2027-1",
        "teachingWeek": 8,
        "termStartMs": 1788105600000,
        "termStartSource": "GUESSED",
        "syncedAt": 0,
        "loggedIn": bool(courses),
        "sessionState": session_state,
        "version": "ui-test",
        "syncing": False,
        "needsResync": False,
        "emptyTerm": False,
        "lastSyncError": "",
        "bgImageUrl": "",
        "bgOpacity": 0.88,
        "bgLuminance": 0.92,
    }
    snap.update(extra)
    return snap


# CDP Emulation.setSafeAreaInsets 支持情况（不支持时明确报告，不做假断言）
SAFE_AREA = {"supported": False, "error": "not attempted", "attempted": False}


def boot(
    browser,
    origin,
    *,
    when=datetime(2026, 10, 24, 8, 40, tzinfo=CST),
    snap=None,
    viewport=None,
    native=True,
    safe_insets=None,
):
    page = browser.new_page(viewport=viewport or {"width": 390, "height": 844})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on(
        "console",
        lambda m: errors.append(m.text)
        if m.type == "error" and not m.text.startswith("Failed to load resource")
        else None,
    )
    page.clock.set_fixed_time(when)
    if safe_insets is not None:
        # 真实 env(safe-area-inset-*)：由浏览器自己算，不用手工改 CSS 变量
        try:
            SAFE_AREA["attempted"] = True
            cdp = page.context.new_cdp_session(page)
            cdp.send("Emulation.setSafeAreaInsetsOverride", {"insets": safe_insets})
            SAFE_AREA["supported"] = True
            SAFE_AREA["error"] = ""
        except Exception as exc:  # noqa: BLE001
            SAFE_AREA["supported"] = False
            SAFE_AREA["error"] = str(exc).strip().splitlines()[0] if str(exc).strip() else repr(exc)
    payload = json.dumps(snap if snap is not None else snapshot())
    bridge = (
        "window.ScheduleBridge = {"
        "  getState: () => JSON.stringify(" + payload + "),"
        "  getOtaState: () => '{}',"
        "  checkUpdate: () => {},"
        "  setSystemBars: (dark) => { window.__themePushes.push(!!dark); }"
        "};"
    )
    # native=False 用来模拟「独立浏览器」：没有原生桥，安全区必须走 env()
    page.add_init_script("window.__themePushes = [];" + (bridge if native else ""))
    page.route("**/*", lambda route: route.continue_() if route.request.url.startswith(origin + "/") else route.abort())
    page.goto(origin + "/assets/www/index.html", wait_until="networkidle")
    page.locator(".app-shell").wait_for(state="visible", timeout=8000)
    return page, errors


def hero_status(page):
    return (page.locator(".hero-status").inner_text() or "").strip()


def hero_title(page):
    return (page.locator(".hero-title").inner_text() or "").strip()


def html_theme(page):
    return page.evaluate("() => document.documentElement.dataset.theme")


def stored_theme(page):
    return page.evaluate("() => window.localStorage.getItem('sztu_theme')")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--screenshot-dir", type=Path)
    args = parser.parse_args()
    failures = []
    if args.screenshot_dir:
        args.screenshot_dir.mkdir(parents=True, exist_ok=True)

    def check(cond, message):
        if not cond:
            raise AssertionError(message)

    def shot(page, name):
        if args.screenshot_dir:
            page.screenshot(path=str(args.screenshot_dir / (name + ".png")))

    with ZipFile(args.apk) as archive:
        server = ThreadingHTTPServer(("127.0.0.1", 0), partial(AssetsHandler, archive=archive))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        origin = f"http://127.0.0.1:{server.server_port}"
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(channel="chrome", headless=True)
                try:
                    # ---------- 1) 主题：点击即切换 + 持久化 + 外观选中态 ----------
                    page, errors = boot(browser, origin)
                    try:
                        check(html_theme(page) == "light", f"默认应为浅色，实际 {html_theme(page)}")
                        page.get_by_role("button", name="切换到深色外观").click()
                        check(html_theme(page) == "dark", "点一次没有变深色")
                        check(stored_theme(page) == "dark", "深色没有被持久化")
                        page.get_by_role("button", name="切换到浅色外观").click()
                        check(html_theme(page) == "light", "再点一次没有变回浅色")
                        check(stored_theme(page) == "light", "浅色没有被持久化")
                        # 原生系统栏同步（受限桥）
                        check(
                            page.evaluate("() => (window.__themePushes || []).length") >= 1,
                            "主题切换没有通知原生系统栏",
                        )

                        # 刷新后保持
                        page.reload(wait_until="networkidle")
                        page.locator(".app-shell").wait_for(state="visible", timeout=8000)
                        check(html_theme(page) == "light", "刷新后没有保持已保存的浅色")

                        # 深色持久化 + 刷新
                        page.get_by_role("button", name="切换到深色外观").click()
                        page.reload(wait_until="networkidle")
                        page.locator(".app-shell").wait_for(state="visible", timeout=8000)
                        check(html_theme(page) == "dark", "刷新后没有保持已保存的深色")
                        shot(page, "theme-dark-today")

                        # 外观页选中态必须反映真实模式（不是永远停在「跟随系统」）
                        page.get_by_label("设置", exact=True).click()
                        page.get_by_role("menuitem", name="外观").click()
                        page.locator(".settings-panel").wait_for(state="visible", timeout=5000)
                        check(
                            (page.locator(".settings-panel .seg-btn.active").inner_text() or "").strip() == "深色",
                            "外观页选中态与实际模式不一致（应为深色）",
                        )
                        page.get_by_role("button", name="跟随系统", exact=True).click()
                        check(
                            (page.locator(".settings-panel .seg-btn.active").inner_text() or "").strip() == "跟随系统",
                            "选择跟随系统后选中态没有更新",
                        )
                        check(stored_theme(page) == "system", "跟随系统没有被持久化")
                        shot(page, "settings-appearance")
                        page.get_by_role("button", name="关闭").click()
                        page.locator(".settings-panel").wait_for(state="detached", timeout=5000)
                        check(not errors, f"页面报错：{errors}")
                    except Exception as error:
                        failures.append(f"theme: {error}")
                    finally:
                        page.close()

                    # ---------- 跟随系统：只有 system 模式跟随系统深浅 ----------
                    page, _ = boot(browser, origin)
                    try:
                        page.get_by_label("设置", exact=True).click()
                        page.get_by_role("menuitem", name="外观").click()
                        page.locator(".settings-panel").wait_for(state="visible", timeout=5000)
                        page.get_by_role("button", name="跟随系统", exact=True).click()
                        page.emulate_media(color_scheme="dark")
                        page.reload(wait_until="networkidle")
                        page.locator(".app-shell").wait_for(state="visible", timeout=8000)
                        check(html_theme(page) == "dark", "system 模式下没有跟随系统深色")
                        page.get_by_role("button", name="切换到浅色外观").click()
                        page.reload(wait_until="networkidle")
                        page.locator(".app-shell").wait_for(state="visible", timeout=8000)
                        check(
                            html_theme(page) == "light",
                            "显式选浅色后不应被系统深色覆盖",
                        )
                    except Exception as error:
                        failures.append(f"system-follow: {error}")
                    finally:
                        page.close()

                    # ---------- 2) 导航：三个入口、无对分易、隐藏面板不可聚焦 ----------
                    page, _ = boot(browser, origin)
                    try:
                        tabs = page.locator("nav.dock .dock-tab")
                        check(tabs.count() == 3, f"Dock 应为 3 个入口，实际 {tabs.count()}")
                        labels = [tabs.nth(i).inner_text().strip() for i in range(tabs.count())]
                        check(labels == ["今日", "课表", "出勤"], f"Dock 标签异常：{labels}")
                        body_text = page.locator(".app-shell").inner_text()
                        check("考勤" not in body_text, "界面仍然出现「考勤」")
                        check(
                            page.evaluate("() => typeof window.DuifeneBridge === 'undefined'"),
                            "仍存在 DuifeneBridge 原生桥",
                        )
                        hidden = page.evaluate(
                            """() => {
                                const el = document.querySelector('.tab-pane-host:not(.active)');
                                return {
                                  inert: el.hasAttribute('inert'),
                                  aria: el.getAttribute('aria-hidden'),
                                };
                            }"""
                        )
                        check(hidden["inert"] is True, "隐藏页面没有 inert，仍可被 Tab 聚焦")
                        check(hidden["aria"] == "true", "隐藏页面没有 aria-hidden")
                        # 隐藏页里的控件不应出现在可聚焦序列里
                        focusable = page.evaluate(
                            """() => {
                                const el = document.querySelector('.tab-pane-host:not(.active)');
                                return el.querySelectorAll('button, [href], input, select, textarea').length;
                            }"""
                        )
                        check(focusable > 0, "测试前提失效：隐藏页里没有可聚焦控件")
                        shot(page, "nav-today")
                        page.locator("nav.dock .dock-tab", has_text="课表").click()
                        page.wait_for_function(
                            "() => document.querySelector('nav [aria-current=page]')?.textContent.includes('课表')"
                        )
                        shot(page, "nav-schedule")
                    except Exception as error:
                        failures.append(f"nav: {error}")
                    finally:
                        page.close()

                    # ---------- 3) 主卡状态语义：08:29 / 08:30 / 09:55 ----------
                    for label, when, expect_status, expect_title in [
                        ("before-start", datetime(2026, 10, 24, 8, 29, tzinfo=CST), "下一节 08:30", "启动回归测试课程"),
                        ("at-start", datetime(2026, 10, 24, 8, 30, tzinfo=CST), "正在上课", "启动回归测试课程"),
                        ("in-class", datetime(2026, 10, 24, 9, 54, tzinfo=CST), "正在上课", "启动回归测试课程"),
                        ("at-end", datetime(2026, 10, 24, 9, 55, tzinfo=CST), "今日课程已结束", "启动回归测试课程"),
                    ]:
                        page, _ = boot(browser, origin, when=when)
                        try:
                            check(
                                hero_status(page) == expect_status,
                                f"{label}: 主卡状态应为 {expect_status!r}，实际 {hero_status(page)!r}",
                            )
                            check(
                                hero_title(page) == expect_title,
                                f"{label}: 主卡标题应为 {expect_title!r}，实际 {hero_title(page)!r}",
                            )
                            if expect_status == "正在上课":
                                check(
                                    page.locator(".course-card.is-live").count() == 1,
                                    f"{label}: 列表没有标出正在上课的课程",
                                )
                            else:
                                check(
                                    page.locator(".course-card.is-live").count() == 0,
                                    f"{label}: 不该标出正在上课",
                                )
                            shot(page, "hero-" + label)
                        except Exception as error:
                            failures.append(f"hero-{label}: {error}")
                        finally:
                            page.close()

                    # ---------- 3b) 周次待核对不能被说成正在上课 ----------
                    unknown = dict(FIXTURE_COURSE, id="ui-unknown", name="周次待核对课程", weeks="实践周")
                    page, _ = boot(
                        browser,
                        origin,
                        when=datetime(2026, 10, 24, 8, 40, tzinfo=CST),
                        snap=snapshot(session_state="SERVER_VERIFIED", courses=[unknown]),
                    )
                    try:
                        check(
                            hero_status(page) == "周次待核对",
                            f"UNKNOWN 周次课程被当成确定上课：{hero_status(page)!r}",
                        )
                        check(
                            page.locator(".course-card.is-live").count() == 0,
                            "UNKNOWN 周次课程被标记为正在上课",
                        )
                        # 用具体元素定位（get_by_text 会把祖先节点也算进来，不能用来数卡片）
                        check(
                            page.locator(".course-card .cc-name").filter(
                                has_text="周次待核对课程"
                            ).count() == 1,
                            "UNKNOWN 周次课程被丢弃，没有保留展示",
                        )
                        check(
                            page.locator(".cc-tag-warn").count() == 1,
                            "UNKNOWN 周次课程没有「待核对」标记",
                        )
                        shot(page, "hero-week-unknown")
                    except Exception as error:
                        failures.append(f"hero-week-unknown: {error}")
                    finally:
                        page.close()

                    # ---------- 4) 动效：减少动画偏好 + 后台暂停 ----------
                    page, _ = boot(browser, origin)
                    try:
                        page.emulate_media(reduced_motion="reduce")
                        page.reload(wait_until="networkidle")
                        page.locator(".app-shell").wait_for(state="visible", timeout=8000)
                        names = page.evaluate(
                            """() => ({
                                petals: getComputedStyle(document.querySelector('.hero-petals')).animationName,
                                badge: getComputedStyle(document.querySelector('.hero-badge')).animationName,
                            })"""
                        )
                        check(
                            names["petals"] == "none" and names["badge"] == "none",
                            f"减少动画偏好下装饰动画没有关闭：{names}",
                        )
                        page.emulate_media(reduced_motion="no-preference")
                        page.reload(wait_until="networkidle")
                        page.locator(".app-shell").wait_for(state="visible", timeout=8000)
                        running = page.evaluate(
                            "() => getComputedStyle(document.querySelector('.hero-badge')).animationPlayState"
                        )
                        check(running == "running", f"装饰动画未在可见时运行：{running}")
                        set_visibility(page, "hidden")
                        paused = page.evaluate(
                            "() => getComputedStyle(document.querySelector('.hero-badge')).animationPlayState"
                        )
                        check(paused == "paused", f"页面隐藏后装饰动画没有暂停：{paused}")
                        set_visibility(page, "visible")
                        resumed = page.evaluate(
                            "() => getComputedStyle(document.querySelector('.hero-badge')).animationPlayState"
                        )
                        check(resumed == "running", f"回到前台后装饰动画没有恢复：{resumed}")
                        # 主卡只有装饰动效，课程卡不应跟着呼吸
                        course_anim = page.evaluate(
                            "() => getComputedStyle(document.querySelector('.course-card')).animationName"
                        )
                        check(course_anim == "none", f"课程卡出现了额外动画：{course_anim}")
                    except Exception as error:
                        failures.append(f"motion: {error}")
                    finally:
                        page.close()

                    # ---------- 5) 设置页全部可达 ----------
                    page, _ = boot(browser, origin)
                    try:
                        for menu_label, title in SETTINGS_PAGES:
                            page.get_by_label("设置", exact=True).click()
                            page.get_by_role("menuitem", name=menu_label).click()
                            page.locator(".settings-panel").wait_for(state="visible", timeout=5000)
                            heading = (page.locator(".settings-head h2").inner_text() or "").strip()
                            check(heading == title, f"{menu_label}: 页面标题应为 {title!r}，实际 {heading!r}")
                            shot(page, "settings-" + menu_label)
                            page.get_by_role("button", name="关闭").click()
                            page.locator(".settings-panel").wait_for(state="detached", timeout=5000)
                    except Exception as error:
                        failures.append(f"settings: {error}")
                    finally:
                        page.close()

                    # ---------- 5b) 安全区所有权：原生已避让，网页不得重复 ----------
                    # 优先用 CDP Emulation.setSafeAreaInsets 造真实 env(safe-area-inset-*)；
                    # 本机 Chrome 不支持该协议时，明确报告并用「同特异性 :root 变量」模拟非零 env，
                    # 断言真正走的是级联（native 标记必须把安全区压回 0），不是检查源码字符串。
                    SAFE_INSETS = {"top": 40, "bottom": 24, "left": 12, "right": 12}
                    USE_CDP = SAFE_AREA.get("supported", False) if SAFE_AREA.get("attempted") else True
                    SIM_STYLE = "html{--safe-top:47px;--safe-bottom:34px;--safe-left:12px;--safe-right:12px;}"

                    def safe_probe(page):
                        return page.evaluate(
                            """() => {
                                const cs = getComputedStyle(document.documentElement);
                                return {
                                  flagged: document.documentElement.dataset.nativeInsets || "",
                                  safeTop: cs.getPropertyValue('--safe-top').trim(),
                                  safeBottom: cs.getPropertyValue('--safe-bottom').trim(),
                                  barPadTop: getComputedStyle(document.querySelector('.top-bar')).paddingTop,
                                  dockBottom: getComputedStyle(document.querySelector('nav.dock')).bottom,
                                  shellPadLeft: getComputedStyle(document.querySelector('.app-shell')).paddingLeft,
                                };
                            }"""
                        )

                    page, _ = boot(browser, origin, safe_insets=SAFE_INSETS)
                    cdp_ok = SAFE_AREA["supported"]
                    if not cdp_ok:
                        print("SAFE-AREA LIMITATION: CDP", SAFE_AREA["error"],
                              "-> 改用同特异性 :root 变量模拟非零 env（47/34/12）")
                    sim = {"top": 40, "bottom": 24, "left": 12} if cdp_ok else {"top": 47, "bottom": 34, "left": 12}
                    try:
                        if not cdp_ok:
                            page.add_style_tag(content=SIM_STYLE)
                        native_geo = safe_probe(page)
                        check(native_geo["flagged"] == "1", "原生环境没有标记 nativeInsets")
                        check(native_geo["safeTop"] == "0px",
                              f"非零安全区下原生环境未归零：{native_geo['safeTop']}")
                        check(native_geo["barPadTop"] == "10px",
                              f"原生环境顶栏出现重复避让（应为 10px）：{native_geo['barPadTop']}")
                        check(native_geo["dockBottom"] == "14px",
                              f"原生环境 Dock 重复避让底部（应为 14px）：{native_geo['dockBottom']}")
                        check(native_geo["shellPadLeft"] == "0px",
                              f"原生环境左右不应再留网页 padding：{native_geo['shellPadLeft']}")
                        page.get_by_label("设置", exact=True).click()
                        page.get_by_role("menuitem", name="登录与同步").click()
                        page.locator(".settings-panel").wait_for(state="visible", timeout=5000)
                        head_pad = page.evaluate(
                            "() => getComputedStyle(document.querySelector('.settings-head')).paddingTop"
                        )
                        check(head_pad == "4px", f"设置面板头部重复避让：{head_pad}")
                        page.get_by_role("button", name="关闭").click()
                        page.locator(".settings-panel").wait_for(state="detached", timeout=5000)
                        shot(page, "safearea-native")
                        print("SAFE-AREA native:", json.dumps(native_geo, ensure_ascii=False))
                    except Exception as error:
                        failures.append(f"safearea-native: {error}")
                    finally:
                        page.close()

                    page, _ = boot(browser, origin, native=False, safe_insets=SAFE_INSETS)
                    try:
                        if not cdp_ok:
                            page.add_style_tag(content=SIM_STYLE)
                        web_geo = safe_probe(page)
                        check(web_geo["flagged"] == "", "独立浏览器不应带 nativeInsets 标记")
                        check(web_geo["safeTop"] == f"{sim['top']}px",
                              f"浏览器没有取到顶部安全区：{web_geo['safeTop']}")
                        check(web_geo["barPadTop"] == f"{10 + sim['top']}px",
                              f"浏览器顶栏应叠加安全区（10+{sim['top']}）：{web_geo['barPadTop']}")
                        check(web_geo["dockBottom"] == f"{14 + sim['bottom']}px",
                              f"浏览器 Dock 应叠加安全区（14+{sim['bottom']}）：{web_geo['dockBottom']}")
                        check(web_geo["shellPadLeft"] == f"{sim['left']}px",
                              f"浏览器横屏左右保护没有生效：{web_geo['shellPadLeft']}")
                        shot(page, "safearea-browser")
                        print("SAFE-AREA browser:", json.dumps(web_geo, ensure_ascii=False))
                    except Exception as error:
                        failures.append(f"safearea-browser: {error}")
                    finally:
                        page.close()
                    # ---------- 6) 宽屏：侧栏 + 右栏与主卡对齐 ----------
                    page, _ = boot(browser, origin, viewport={"width": 1440, "height": 900})
                    try:
                        check(page.locator("nav.side-nav").count() == 1, "宽屏没有侧栏")
                        check(page.locator("nav.dock").count() == 0, "宽屏仍渲染了移动 Dock")
                        geo = page.evaluate(
                            """() => {
                                const hero = document.querySelector('.hero').getBoundingClientRect();
                                const head = document.querySelector('.section-head').getBoundingClientRect();
                                const list = document.querySelector('.course-list')?.getBoundingClientRect();
                                return { heroTop: hero.top, headTop: head.top, listTop: list ? list.top : null,
                                         headBottom: head.bottom };
                            }"""
                        )
                        check(
                            abs(geo["heroTop"] - geo["headTop"]) <= 24,
                            f"宽屏右栏标题没有与主卡顶部对齐：hero={geo['heroTop']}, head={geo['headTop']}",
                        )
                        check(
                            geo["listTop"] is not None and geo["listTop"] - geo["headBottom"] <= 16,
                            f"宽屏标题与首个课程卡之间出现大断层：{geo}",
                        )
                        shot(page, "wide-today")
                        page.locator("nav.side-nav .side-item", has_text="课表").click()
                        page.wait_for_function(
                            "() => document.querySelector('nav [aria-current=page]')?.textContent.includes('课表')"
                        )
                        # 静止时相邻周不得从两侧边距里露出（裁剪 viewport 必须无 padding）
                        bleed = page.evaluate(
                            """() => {
                                const outer = document.querySelector('.grid-outer');
                                const wrap = document.querySelector('.grid-wrap');
                                const cs = getComputedStyle(wrap);
                                return {
                                  wrapPad: [cs.paddingLeft, cs.paddingRight],
                                  width: wrap.clientWidth,
                                  track: document.querySelector('.slide-track').scrollWidth,
                                };
                            }"""
                        )
                        check(
                            bleed["wrapPad"] == ["0px", "0px"],
                            f"裁剪 viewport 仍有 padding，会露出相邻周：{bleed['wrapPad']}",
                        )
                        check(
                            abs(bleed["track"] - bleed["width"] * 3) <= 3,
                            f"三页轨道宽度与可视宽度不一致（会裁切/偏位）：{bleed}",
                        )
                        shot(page, "wide-schedule")
                    except Exception as error:
                        failures.append(f"wide: {error}")
                    finally:
                        page.close()
                finally:
                    browser.close()
        finally:
            server.shutdown()
            server.server_close()

    for failure in failures:
        print(failure)
    if failures:
        raise SystemExit(1)
    print(
        "PASS: 主题点击/持久化/跟随系统、3 入口导航与隐藏面板 inert、主卡 08:29-09:55 边界、"
        "周次待核对语义、减少动画与后台暂停、全部设置页、宽屏对齐与裁剪均通过。"
    )


if __name__ == "__main__":
    main()
