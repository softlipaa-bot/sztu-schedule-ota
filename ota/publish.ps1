# Publish SZTU schedule OTA -> GitHub Releases + update.json
param(
    [string]$ApkPath = "",
    [string]$Repo = "softlipaa-bot/sztu-schedule-ota",
    [string]$Changelog = "",
    [switch]$WriteGradle
)

$ErrorActionPreference = "Stop"
$env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")

$Root = Split-Path -Parent $PSScriptRoot
$Public = Join-Path $PSScriptRoot "public"
New-Item -ItemType Directory -Force -Path $Public | Out-Null

if (-not $ApkPath) {
    $ApkPath = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
}
if (-not (Test-Path $ApkPath)) {
    throw "APK not found: $ApkPath (run assembleDebug first)"
}

$metaPath = Join-Path $Root "app\build\outputs\apk\debug\output-metadata.json"
$versionCode = 0
$versionName = ""
if (Test-Path $metaPath) {
    $meta = Get-Content $metaPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $el = $meta.elements[0]
    $versionCode = [int]$el.versionCode
    $versionName = [string]$el.versionName
}
if ($versionCode -le 0) {
    throw "Cannot read versionCode from output-metadata.json"
}

if (-not $Changelog) {
    $Changelog = "SZTU Schedule $versionName"
}

$tag = "v$versionName"
$assetName = "sztu-schedule-latest.apk"
$destApk = Join-Path $Public $assetName
Copy-Item -Force $ApkPath $destApk
Copy-Item -Force $ApkPath (Join-Path $Public ("sztu-schedule-v{0}.apk" -f $versionName))

# 发布的 manifest 必须能对上真正上传的那个文件：
# 记录字节数与 SHA-256，OtaUpdater 下载完成后会据此校验（缺字段则跳过校验）。
$sizeBytes = (Get-Item $destApk).Length
$sha256 = (Get-FileHash $destApk -Algorithm SHA256).Hash.ToLowerInvariant()

$repoExists = $false
try {
    gh repo view $Repo 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $repoExists = $true }
} catch {}
if (-not $repoExists) {
    gh repo create $Repo --public --description "SZTU schedule OTA (update.json + APK)" --add-readme
}

$work = Join-Path $PSScriptRoot "github-dist"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
gh repo clone $Repo $work -- --depth 1
if (-not (Test-Path $work)) {
    throw "Failed to clone $Repo"
}

$apkUrl = "https://github.com/$Repo/releases/download/$tag/$assetName"
$manifestUrl = "https://raw.githubusercontent.com/$Repo/main/update.json"

$json = @"
{
  "versionCode": $versionCode,
  "versionName": "$versionName",
  "apkUrl": "$apkUrl",
  "force": false,
  "sizeBytes": $sizeBytes,
  "sha256": "$sha256",
  "assetName": "$assetName",
  "localPath": "ota/public/$assetName",
  "changelog": "$($Changelog.Replace('"','\"'))"
}
"@
$jsonPath = Join-Path $Public "update.json"
$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($jsonPath, $json.Trim() + "`n", $utf8)
Copy-Item -Force $jsonPath (Join-Path $work "update.json")

Push-Location $work
try {
    git config user.email "ota@sztu.local"
    git config user.name "sztu-schedule-ota"
    git add update.json
    $status = git status --porcelain
    if ($status) {
        git commit -m "ota: $tag ($versionCode)"
        git push origin HEAD:main
    }
} finally {
    Pop-Location
}

$releaseExists = $false
try {
    gh release view $tag --repo $Repo 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $releaseExists = $true }
} catch {}
if ($releaseExists) {
    gh release delete $tag --repo $Repo --yes
}
gh release create $tag $destApk --repo $Repo --title $tag --notes $Changelog

[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot "last-manifest-url.txt"), $manifestUrl + "`n", $utf8)

if ($WriteGradle) {
    $gp = Join-Path $Root "gradle.properties"
    $lines = @()
    if (Test-Path $gp) {
        $lines = Get-Content $gp | Where-Object { $_ -notmatch '^\s*OTA_MANIFEST_URL\s*=' }
    }
    $lines += "OTA_MANIFEST_URL=$manifestUrl"
    [System.IO.File]::WriteAllLines($gp, $lines, $utf8)
    Write-Host "Wrote gradle.properties OTA_MANIFEST_URL=$manifestUrl"
}

Write-Host "OTA published to GitHub:"
Write-Host "  version : $versionName ($versionCode)"
Write-Host "  release : https://github.com/$Repo/releases/tag/$tag"
Write-Host "  manifest: $manifestUrl"
Write-Host "  apkUrl  : $apkUrl"
