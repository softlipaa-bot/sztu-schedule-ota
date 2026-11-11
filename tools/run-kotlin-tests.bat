@echo off
REM ---------------------------------------------------------------------------
REM Run Kotlin/JUnit unit tests from an ASCII subst drive.
REM
REM Why: this project lives under a non-ASCII path (D:\??app\SZTU??), and the
REM Gradle unit-test worker fails with ClassNotFoundException there while the
REM exact same classpath works when launched manually. Mapping the project to an
REM ASCII drive letter makes `gradlew testDebugUnitTest` work reliably.
REM
REM Usage:  tools\run-kotlin-tests.bat [extra gradle args...]
REM Exit code is forwarded from Gradle.
REM ---------------------------------------------------------------------------
setlocal EnableExtensions

set "ROOT=%~dp0.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"

if not defined ANDROID_HOME (
  echo [info] ANDROID_HOME not set; Gradle will fall back to local.properties (/sdk.dir)
)

set "LETTER="
for %%D in (T U V W X Y Z) do (
  if not defined LETTER (
    if not exist %%D:\ (
      subst %%D: "%ROOT%" >nul 2>&1
      if not errorlevel 1 set "LETTER=%%D"
    )
  )
)
if not defined LETTER (
  echo [error] no free drive letter for subst ^(tried T..Z^)
  exit /b 1
)

echo [info] project mapped to %LETTER%:\ for the test run
pushd "%LETTER%:\" || goto :cleanup_fail

call gradlew.bat --offline testDebugUnitTest %*
set "CODE=%ERRORLEVEL%"

popd

:cleanup
subst %LETTER%: /d >nul 2>&1
if not "%CODE%"=="0" (
  echo [fail] unit tests failed, exit code %CODE%
) else (
  echo [ok] unit tests passed
)
exit /b %CODE%

:cleanup_fail
subst %LETTER%: /d >nul 2>&1
echo [error] cannot enter %LETTER%:\
exit /b 1
