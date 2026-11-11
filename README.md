# SZTU教务 / 深技大课表

当前公开源码版本：**2.2.1**。源码位于本仓库，安装包见 [Releases](https://github.com/softlipaa-bot/sztu-schedule-ota/releases)。

## 构建

安装 JDK 17+、Android SDK 和 Node.js。配置本机 `local.properties`，运行 `cd web`、`npm ci`、`npm run build`，回到根目录运行 `gradlew.bat assembleDebug`。

## 发布说明

源码来自当前本地工作目录。个人账号数据、签名私钥、本机 SDK 路径、依赖缓存和历史安装包不随源码上传。第三方依赖遵循各自许可证。

本次 Android 安装包是已签名的 Debug 构建，并非 Release 构建。

## 许可证

原创项目代码采用 [MIT License](LICENSE)。第三方代码、依赖及素材的原有权利和许可证继续适用。

## 原项目说明

# 深技大课表（SZTU教务）

单页课表 App（官方 SSO 登录 → 网页爬取课表）+ 澎湃/红米桌面小部件。
当前版本 **2.2.1（versionCode 67）**，UI 为 FlClash 风格的柔和卡片界面。

2.2.1 修正出勤同步跑在主线程上、从而显示「获取失败」的问题。从出勤页同步时先登录教学辅助系统；教务和出勤一边失败不会清掉另一边。学期起止日期在你确认之前会标成待确认。

## 功能
- 统一认证登录（WebView，官方 SSO）
- 解析教务课表页并本地缓存（原子写 + 备份兜底）
- 登录与同步：教务课表和教学辅助出勤各自登录，成功后一起同步
- 出勤记录：按学期查看，默认只看已确认缺勤
- 桌面小部件：上节 / 这节 / 下节 + 今日时段
- 课表背景图（相册选图 → 裁切 → 受限本地端点提供，卡片保持实色可读）
- 教学楼地图（双指缩放 / 双击缩放）
- 校历起点校准（影响今日页、周课表与小组件）
- 在线更新（OTA manifest 带 sizeBytes / sha256 校验）
- 浅色 / 深色 / 跟随系统三态外观，顶部按钮一点即浅↔深切换

**自 2.1.0 起不再包含考勤（对分易）功能**：没有考勤 Tab、没有前台服务、没有 Python/Chaquopy 引擎，
也不再声明前台服务 / 唤醒锁 / 通知权限。老版本升级上来时会在首次启动取消遗留的旧通知与通知渠道。

## 构建 APK
```bat
REM 1) 先构建前端（输出到 app/src/main/assets/www）
cd web && npm run build && cd ..

REM 2) 再打包
set ANDROID_HOME=D:\MovedFromC\AppDataLocal\Android\Sdk
gradlew.bat assembleDebug
```
产物：`app\build\outputs\apk\debug\app-debug.apk`

Kotlin 单测（中文路径下必须走这个入口）：
```bat
tools\run-kotlin-tests.bat
```

前端回归（需要 Python Playwright + 本机 Chrome，跑的是 APK 内真实资源 + 假原生桥）：
```bat
python -X utf8 tools\test-web-startup.py --apk <apk>
python -X utf8 tools\test-web-ui.py      --apk <apk>
```

## 红米 / 澎湃
1. 安装 APK 后打开 App，在设置 → 关于 里点「检查更新」确认版本
2. 手动添加小部件：桌面双指捏合 → 小部件 → 搜索「SZTU教务」
3. 建议允许无限制电量、自启动，避免系统清理后台导致小部件不刷新

## 界面结构
- 今日：主卡（真实上课状态 + 装饰性花形徽章）、状态条、本学期出勤摘要、今日课程列表
- 课表：周课表（左右滑动换周）+ 周次导航 + 返回本周
- 出勤：学期选择、查询范围、已确认缺勤和记录列表
- 设置：登录与同步 / 外观 / 选择背景 / 教学楼地图 / 校历起点 / 关于

宽屏（≥1000px）使用左侧栏 + 两栏内容；手机使用浮动 Dock（今日 / 课表 / 出勤）。
设计令牌集中在 `web/src/styles/tokens.css`，组件样式在 `web/src/styles/app.css`。
