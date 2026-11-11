"""Exercise the actual APK's WebView assets in Chromium with fake native state.

Requires Python Playwright and an installed Chrome. Does not use real accounts.
Example: python tools/test-web-startup.py --apk final-sztu-2.0.36.apk
This is a frontend startup check, not an Android device integration test.

Covered cases (all with a fake ScheduleBridge, no network, no login):
  empty-cache   首次启动、无缓存 → 首帧可交互，不报错
  cached-course 有缓存课表 → 课程渲染 + 今日/课表可切换
  bg-ready      背景图走受限端点地址（不是 data URL）→ 预解码完成后才渲染 .bg-layer

The background image check is what guards review item J29-01: the APK must no
longer ship a base64 `bgImage` payload through the bridge, and the shell must
paint without a background until the image is decoded.
"""
import argparse
from datetime import datetime, timezone, timedelta
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
from pathlib import Path
import threading
from urllib.parse import unquote, urlsplit
from zipfile import ZipFile

from playwright.sync_api import sync_playwright

# 受控本地端点：APK 里的真实静态资源，用来当"背景图就绪"的替身
BG_FIXTURE_PATH = "assets/www/assets/campus-map-CHGA0Dm2.jpg"


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


TIMER_PROBE = """
window.__timerProbe = { intervals: 0 };
(() => {
  const realSet = window.setInterval.bind(window);
  const realClear = window.clearInterval.bind(window);
  const ids = new Set();
  window.setInterval = function (...a) {
    const id = realSet(...a);
    ids.add(id);
    window.__timerProbe.intervals = ids.size;
    return id;
  };
  window.clearInterval = function (id) {
    try { realClear(id); } catch (e) {}
    ids.delete(id);
    window.__timerProbe.intervals = ids.size;
  };
})();
"""


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--screenshot-dir", type=Path)
    args = parser.parse_args()
    failures = []
    if args.screenshot_dir:
        args.screenshot_dir.mkdir(parents=True, exist_ok=True)
    cases = [
        # label, 有缓存课表, 背景图地址, 首页状态条应出现的标题（None = 不出现状态条）
        {"label": "empty-cache", "cached": False, "bg": "", "banner": "未登录",
         "session_state": "NONE"},
        {"label": "cached-course", "cached": True, "bg": "", "banner": "缓存可看",
         "session_state": "CACHED_ONLY"},
        {"label": "bg-ready", "cached": True, "bg": "/" + BG_FIXTURE_PATH,
         "banner": "缓存可看", "session_state": "CACHED_ONLY"},
        # J29-02：同步失败 / 解析需核对 必须与「未登录」「缓存可看」分开显示
        {"label": "sync-error", "cached": True, "bg": "", "banner": "同步失败",
         "session_state": "CACHED_ONLY", "last_sync_error": "网络错误：连接超时"},
        {"label": "needs-resync", "cached": True, "bg": "", "banner": "解析需核对",
         "session_state": "SERVER_VERIFIED", "needs_resync": True},
        {"label": "session-verified", "cached": True, "bg": "", "banner": None,
         "session_state": "SERVER_VERIFIED"},
    ]
    with ZipFile(args.apk) as archive:
        if BG_FIXTURE_PATH not in archive.namelist():
            failures.append(f"fixture asset missing from APK: {BG_FIXTURE_PATH}")
        server = ThreadingHTTPServer(("127.0.0.1", 0), partial(AssetsHandler, archive=archive))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(channel="chrome", headless=True)
                try:
                    for case in cases:
                        label = case["label"]
                        cached = case["cached"]
                        bg_url = case["bg"]
                        page = browser.new_page(viewport={"width": 412, "height": 915})
                        errors = []
                        page.on("pageerror", lambda error: errors.append(str(error)))
                        # Vue reports setup errors through console.error in production builds.
                        page.on("console", lambda message: errors.append(message.text) if message.type == "error" and not message.text.startswith("Failed to load resource") else None)
                        page.clock.set_fixed_time(datetime(2026, 10, 24, 10, tzinfo=timezone(timedelta(hours=8))))
                        snapshot = {
                            "courses": [], "term": "2026-2027-1", "teachingWeek": 8,
                            "termStartMs": 1788105600000, "termStartSource": "GUESSED",
                            "syncedAt": 0, "loggedIn": cached,
                            "sessionState": case.get("session_state", "NONE"),
                            "version": "startup-test", "syncing": False,
                            "needsResync": bool(case.get("needs_resync")),
                            "emptyTerm": bool(case.get("empty_term")),
                            "lastSyncError": case.get("last_sync_error", ""),
                            # J29-01：背景只发受限端点地址，绝不发 data URL
                            "bgImageUrl": bg_url, "bgOpacity": 0.88, "bgLuminance": 0.92,
                        }
                        if cached:
                            snapshot["courses"] = [{
                                "id": "startup-fixture", "name": "启动回归测试课程", "teacher": "测试教师",
                                "place": "测试教室", "weeks": "1-18(周)[01-02节]", "weekday": 6,
                                "startSection": 1, "endSection": 2, "startTime": "08:30", "endTime": "09:55",
                                "sectionLabel": "第1-2节", "timeLabel": "08:30–09:55",
                            }]
                        page.add_init_script(TIMER_PROBE)
                        page.add_init_script("window.ScheduleBridge = { getState: () => JSON.stringify(" + json.dumps(snapshot) + "), getOtaState: () => '{}', checkUpdate: () => {} };")
                        origin = f"http://127.0.0.1:{server.server_port}"
                        page.route("**/*", lambda route: route.continue_() if route.request.url.startswith(origin + "/") else route.abort())
                        detail = {"case": label, "pageErrors": errors}
                        try:
                            page.goto(origin + "/assets/www/index.html", wait_until="networkidle")
                            page.locator(".app-shell").wait_for(state="visible", timeout=5000)
                            page.get_by_role("button", name="课表", exact=True).click()
                            # 2.1.0 起主导航是浮动 Dock / 宽屏侧栏，按钮不再是 .tab；
                            # 用 aria-current（当前页语义）定位，两套导航都成立。
                            page.wait_for_function("document.querySelector('nav [aria-current=page]')?.textContent.includes('课表')")
                            page.wait_for_function("Math.abs(new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.tab-pane-host.active')).transform).m41) < 0.5")
                            if cached and page.get_by_text("启动回归测试课程", exact=True).count() == 0:
                                raise AssertionError("Cached course was not rendered")
                            page.get_by_role("button", name="今日", exact=True).click()
                            page.wait_for_function("document.querySelector('nav [aria-current=page]')?.textContent.includes('今日')")
                            page.wait_for_function("Math.abs(new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.tab-pane-host.active')).transform).m41) < 0.5")

                            # ---- J29-02：首页状态条区分未登录/缓存可看/同步失败/解析需核对 ----
                            expected_banner = case.get("banner")
                            banner_text = page.evaluate(
                                "() => (document.querySelector('.home-banner .hb-title')?.textContent || '').trim()"
                            )
                            detail["banner"] = banner_text
                            if expected_banner is None:
                                if page.locator(".home-banner").count() != 0:
                                    raise AssertionError(
                                        f"unexpected home banner: {banner_text!r}"
                                    )
                            elif banner_text != expected_banner:
                                raise AssertionError(
                                    f"home banner should be {expected_banner!r}, got {banner_text!r}"
                                )
                            if case.get("last_sync_error"):
                                shown = page.evaluate(
                                    "() => document.querySelector('.home-banner .hb-desc')?.textContent || ''"
                                )
                                if case["last_sync_error"] not in shown:
                                    raise AssertionError(
                                        f"sync error text not surfaced on home: {shown!r}"
                                    )
                            # 「去登录 / 重新同步」是新增业务入口，未获用户批准前必须默认关闭
                            # （开关：web/src/App.vue 的 HOME_BANNER_ACTIONS）
                            action_buttons = page.locator(".home-banner .hb-action").count()
                            detail["bannerActions"] = action_buttons
                            if action_buttons != 0:
                                raise AssertionError(
                                    "home banner action buttons are enabled without user approval"
                                )

                            # ---- J29-01：背景图必须走受限端点，且就绪后才绘制 ----
                            has_bg = page.locator(".app-shell.has-bg").count() > 0
                            bg_layer = page.locator(".bg-layer").count()
                            css_bg = page.evaluate(
                                "() => getComputedStyle(document.querySelector('.app-shell')).getPropertyValue('--bg-image')"
                            ) if has_bg else ""
                            if "data:" in css_bg:
                                raise AssertionError("background still uses a data URL (J29-01 regression)")
                            if bg_url:
                                page.wait_for_selector(".bg-layer", timeout=5000)
                                page.wait_for_function(
                                    "() => getComputedStyle(document.querySelector('.app-shell')).getPropertyValue('--bg-image').includes('/assets/')",
                                    timeout=5000,
                                )
                                # 淡入结束（240ms）后再断言浓度，避免读到动画中间值
                                page.wait_for_function(
                                    "() => Number(getComputedStyle(document.querySelector('.bg-layer')).opacity) > 0.2",
                                    timeout=5000,
                                )
                                opacity = page.evaluate(
                                    "() => Number(getComputedStyle(document.querySelector('.bg-layer')).opacity)"
                                )
                                if not (opacity > 0.2):
                                    raise AssertionError(f"bg-layer opacity too low: {opacity}")
                                detail["bgOpacityComputed"] = opacity
                                # 淡入结束后必须能实时改浓度（若动画用了 fill 模式，
                                # 这里会卡在动画末帧而调不动）
                                page.evaluate(
                                    "() => document.querySelector('.app-shell').style.setProperty('--bg-opacity','0.5')"
                                )
                                page.wait_for_function(
                                    "() => Number(getComputedStyle(document.querySelector('.bg-layer')).opacity) < 0.6",
                                    timeout=3000,
                                )
                                detail["bgOpacityAfterOverride"] = page.evaluate(
                                    "() => Number(getComputedStyle(document.querySelector('.bg-layer')).opacity)"
                                )
                            elif bg_layer != 0:
                                raise AssertionError("bg-layer rendered without an image URL")
                            detail["hasBg"] = has_bg
                            detail["bgLayer"] = bg_layer

                            # ---- J29-02：不可见时停表，回来时恢复 ----
                            before = page.evaluate("() => window.__timerProbe.intervals")
                            set_visibility(page, "hidden")
                            hidden = page.evaluate("() => window.__timerProbe.intervals")
                            set_visibility(page, "visible")
                            visible = page.evaluate("() => window.__timerProbe.intervals")
                            detail["timers"] = {"before": before, "hidden": hidden, "visible": visible}
                            if before < 1:
                                raise AssertionError("no 30s clock timer was registered while visible")
                            if hidden >= before:
                                raise AssertionError("clock timer kept running while the page was hidden (J29-02)")
                            if visible <= hidden:
                                raise AssertionError("clock timer was not restarted after the page became visible")
                        except Exception as error:
                            failures.append(f"{label}: {error}")
                        if args.screenshot_dir:
                            page.screenshot(path=str(args.screenshot_dir / (label + ".png")))
                        if errors:
                            failures.append(f"{label}: JavaScript errors: {errors}")
                        detail["pageErrors"] = errors
                        detail["rendered"] = page.locator(".app-shell").count() > 0
                        print(json.dumps(detail, ensure_ascii=False))
                        page.close()
                finally:
                    browser.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
    for failure in failures:
        print(failure)
    if failures:
        raise SystemExit(1)
    print(
        "PASS: APK frontend starts with empty and populated caches, renders the "
        "restricted background endpoint URL (no data URL), pauses the clock while "
        "hidden, and navigation works."
    )


if __name__ == "__main__":
    main()
