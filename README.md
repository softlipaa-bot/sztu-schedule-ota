# 深技大课表 OTA / SZTU Schedule OTA

中文 | [English](#sztu-schedule-ota-english)

面向 **深技大课表（SZTU Schedule）** Android 客户端的公开 OTA 发布仓库。模式与 CELLULAR‑Z 等 softlipaa‑bot OTA 仓库一致：清单在 `main`，安装包在 GitHub Release。

---

## 项目简介

深技大课表是面向深圳技术大学师生的课程表 Android 应用。本仓库**仅托管更新通道**，不包含应用业务源码：

| 内容 | 说明 |
|------|------|
| `update.json` | 客户端检查更新时读取的版本清单 |
| GitHub Releases | APK 安装包（如 `sztu-schedule-latest.apk`） |

清单地址（客户端写死或配置）：

```text
https://raw.githubusercontent.com/softlipaa-bot/sztu-schedule-ota/main/update.json
```

## update.json 字段说明

| 字段 | 说明 |
|------|------|
| `versionCode` | 整数；大于本地已装版本时提示更新 |
| `versionName` | 版本名，如 `2.0.16` |
| `apkUrl` | Release APK 直链 |
| `force` | 是否强制更新 |
| `changelog` | 更新日志文案 |

当前清单示例结构：

```json
{
  "versionCode": 43,
  "versionName": "2.0.16",
  "apkUrl": "https://github.com/softlipaa-bot/sztu-schedule-ota/releases/download/v2.0.16/sztu-schedule-latest.apk",
  "force": false,
  "changelog": "GitHub OTA; check update in About"
}
```

## 如何发版

1. 在应用工程中升高 `versionCode` / `versionName` 并打出 APK
2. 本仓库新建 Release（标签如 `v2.0.16`），上传 `sztu-schedule-latest.apk`
3. 提交并推送更新后的 `update.json` 到 `main`
4. 打开课表 App → **关于**（或设置中的检查更新入口）→ 检查更新

## 设计约定

- **公开仓库**：便于 raw.githubusercontent.com 直链拉取，无需鉴权
- **清单与包分离**：`update.json` 轻量；大文件走 Release Assets
- **versionCode 为准**：展示名可改，比较逻辑以整数版本码为准

## 相关链接

- 仓库：https://github.com/softlipaa-bot/sztu-schedule-ota
- Releases：https://github.com/softlipaa-bot/sztu-schedule-ota/releases

---

<a id="sztu-schedule-ota-english"></a>

# SZTU Schedule OTA (English)

Public **OTA distribution** repo for the **SZTU Schedule** Android app (Shenzhen Technology University course timetable). Same pattern as other softlipaa‑bot OTA repos: manifest on `main`, APK on GitHub Releases.

## Overview

This repository hosts **only the update channel**, not the app’s business source code:

| Artifact | Role |
|----------|------|
| `update.json` | Version manifest fetched by the client |
| GitHub Releases | APK packages (e.g. `sztu-schedule-latest.apk`) |

Manifest URL:

```text
https://raw.githubusercontent.com/softlipaa-bot/sztu-schedule-ota/main/update.json
```

## Manifest fields

| Field | Description |
|-------|-------------|
| `versionCode` | Integer; update prompts when remote > local |
| `versionName` | Display version (e.g. `2.0.16`) |
| `apkUrl` | Direct link to the Release APK |
| `force` | Forced‑update flag |
| `changelog` | Notes shown in the updater UI |

## Publishing a release

1. Bump versions in the app project and build the APK
2. Create a Release here (tag e.g. `v2.0.16`) and upload `sztu-schedule-latest.apk`
3. Push an updated `update.json` to `main`
4. In the app: **About** (or Check for updates) → verify the new build

## Conventions

- Public repo for unauthenticated raw GitHub fetches
- Small JSON manifest; large binaries on Release assets
- `versionCode` is the source of truth for comparisons

## Links

- Repository: https://github.com/softlipaa-bot/sztu-schedule-ota
- Releases: https://github.com/softlipaa-bot/sztu-schedule-ota/releases
