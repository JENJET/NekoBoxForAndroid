@echo off
setlocal enabledelayedexpansion

set "PROJECT_ROOT=%~dp0"
set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"

rem signing config (from nb4a.properties)
for /f "tokens=1,* delims==" %%a in ('type "%PROJECT_ROOT%\nb4a.properties"') do (
    if "%%a"=="KEYSTORE_PASS" set "KEYSTORE_PASS=%%b"
    if "%%a"=="ALIAS_NAME" set "ALIAS_NAME=%%b"
    if "%%a"=="ALIAS_PASS" set "ALIAS_PASS=%%b"
)

rem sing-box version
set "SING_BOX_VERSION=1.13.13"

rem temp dir for intermediate files (cleaned up on exit)
set "TEMP_DIR=%PROJECT_ROOT%\.build_tmp"
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
mkdir "%TEMP_DIR%"

echo ========================================
echo  NekoBoxForAndroid Windows Build Script
echo ========================================
echo.

rem ============================================================
rem Parse arguments
rem ============================================================
set NO_LIBCORE=0
set NO_GRADLE=0
set FAST=0
set BUILD_TYPE=oss

:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="-NoLibcore"  set NO_LIBCORE=1
if /i "%~1"=="-NoGradle"   set NO_GRADLE=1
if /i "%~1"=="-Fast"       set FAST=1
if /i "%~1"=="-BuildType" (
    shift
    if /i "%~1"=="preview" set BUILD_TYPE=preview
    if /i "%~1"=="play"    set BUILD_TYPE=play
)
shift
goto :parse_args
:args_done

rem ============================================================
rem 1. Check prerequisites
rem ============================================================
set HAS_ERROR=0

rem --- Go ---
where go >nul 2>nul
if %ERRORLEVEL%==0 (
    for /f "delims=" %%i in ('go version') do set "GO_VER=%%i"
    if defined GO_VER (
        echo [OK] Go: !GO_VER!
    ) else (
        echo [OK] Go: found
    )
) else (
    echo [FAIL] Go not found. Install Go 1.24+ from https://go.dev/dl/
    set HAS_ERROR=1
)

rem --- Java ---
set "JAVA_CMD=java.exe"
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    if not "%JAVA_HOME%"=="" (
        if exist "%JAVA_HOME%\bin\java.exe" (
            set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
        )
    )
)
set "JAVA_FOUND=0"
if "%JAVA_CMD%"=="java.exe" (
    where java >nul 2>nul
    if not errorlevel 1 set JAVA_FOUND=1
) else (
    if exist "%JAVA_CMD%" set JAVA_FOUND=1
)
if %JAVA_FOUND%==0 (
    echo [FAIL] Java/JDK not found. Install JDK 17+ from https://adoptium.net/
    set HAS_ERROR=1
) else (
    "%JAVA_CMD%" -version 2>&1 | findstr /b "openjdk java" > "%TEMP%\java_ver.txt"
    set "JAVA_VER="
    set /p JAVA_VER=<"%TEMP%\java_ver.txt"
    if defined JAVA_VER (
        echo [OK] Java: !JAVA_VER!
    ) else (
        echo [OK] Java found
    )
)

rem ensure JAVA_HOME is set for Gradle
if "%JAVA_HOME%"=="" if "%JAVA_CMD%"=="java.exe" (
    for /f "delims=" %%i in ('where java') do set "JAVA_HOME=%%~dpi.."
)

rem --- Android SDK ---
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)
if not "%ANDROID_HOME%"=="" (
    if exist "%ANDROID_HOME%" (
        echo [OK] ANDROID_HOME: %ANDROID_HOME%
    ) else (
        echo [FAIL] ANDROID_HOME set but not found: %ANDROID_HOME%
        set HAS_ERROR=1
    )
) else (
    echo [FAIL] ANDROID_HOME not set
    set HAS_ERROR=1
)

rem --- Android NDK ---
if not "%ANDROID_HOME%"=="" (
    set "NDK_FOUND="
    for /f "delims=" %%d in ('dir "%ANDROID_HOME%\ndk" /b 2^>nul') do (
        if exist "%ANDROID_HOME%\ndk\%%d\source.properties" (
            set "NDK_FOUND=%ANDROID_HOME%\ndk\%%d"
        )
    )
    if defined NDK_FOUND (
        set "ANDROID_NDK_HOME=!NDK_FOUND!"
        echo [OK] NDK: !NDK_FOUND!
    ) else (
        echo [FAIL] No NDK found in %ANDROID_HOME%\ndk
        echo   Install via SDK Manager or command: sdkmanager "ndk;25.2.9519653"
        set HAS_ERROR=1
    )
)

if %HAS_ERROR%==1 (
    echo.
    echo Please fix the errors above and re-run.
    exit /b 1
)

rem ============================================================
rem 2. Add Java to PATH and install gomobile
rem ============================================================
for /f "delims=" %%i in ('go env GOPATH') do set "GOPATH=%%i"
set "GOMOBILE=%GOPATH%\bin\gomobile.exe"
if not "%JAVA_HOME%"=="" (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

if not exist "%GOMOBILE%" (
    echo.
    echo ^>^>^> Installing gomobile ^(official^)...
    go install golang.org/x/mobile/cmd/gomobile@latest
    if errorlevel 1 (
        echo [FAIL] gomobile install failed
        exit /b 1
    )
    if not exist "%GOMOBILE%" (
        echo [FAIL] gomobile not found after install
        exit /b 1
    )
    echo [OK] gomobile installed
) else (
    echo [OK] gomobile: %GOMOBILE%
)

rem --- gomobile init ---
if "%FAST%"=="0" (
    echo.
    echo ^>^>^> gomobile init...
    "%GOMOBILE%" init
    if %ERRORLEVEL% neq 0 (
        echo [FAIL] gomobile init failed. Check NDK installation.
        exit /b 1
    )
    echo [OK] gomobile init done
) else (
    echo.
    echo [SKIP] gomobile init ^(--Fast^)
)

rem ============================================================
rem 3. Build libcore (Go -> AAR)
rem ============================================================
if "%NO_LIBCORE%"=="1" goto :skip_libcore

echo.
echo ========================================
echo  Building libcore (Go -^> libcore.aar)
echo ========================================

for %%I in ("%PROJECT_ROOT%\..") do set "PARENT=%%~fI"
if not exist "%PARENT%\sing-box" (
    echo [FAIL] sing-box not found at "%PARENT%\sing-box"
    echo   Clone: git clone --depth 1 --branch v!SING_BOX_VERSION! https://github.com/SagerNet/sing-box.git
    exit /b 1
)
if not exist "%PARENT%\libneko" (
    echo [FAIL] libneko not found at "%PARENT%\libneko"
    echo   Clone: git clone https://github.com/MatsuriDayo/libneko.git
    exit /b 1
)

pushd "%PROJECT_ROOT%\libcore"

if exist ".build"           rmdir /s /q ".build"
if exist "libcore.aar"      del /q "libcore.aar"
if exist "libcore-sources.jar" del /q "libcore-sources.jar"

echo.
echo ^>^>^> gomobile bind...
"%GOMOBILE%" bind -v -androidapi 21 -trimpath -ldflags=-w -tags=with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api .
if errorlevel 1 (
    popd
    echo [FAIL] gomobile bind failed
    exit /b 1
)

if exist "libcore-sources.jar" del /q "libcore-sources.jar"

if not exist "%PROJECT_ROOT%\app\libs" mkdir "%PROJECT_ROOT%\app\libs"
copy /y "libcore.aar" "%PROJECT_ROOT%\app\libs\libcore.aar" >nul
echo [OK] libcore.aar -^> app\libs\libcore.aar

popd

:skip_libcore
if "%NO_LIBCORE%"=="1" echo [SKIP] libcore build (--NoLibcore)

rem ============================================================
rem 4. Download geo assets
rem ============================================================
echo.
echo ========================================
echo  Downloading geo assets
echo ========================================

set "ASSETS=%PROJECT_ROOT%\app\src\main\assets\sing-box"
if not exist "%ASSETS%" mkdir "%ASSETS%"

rem find xz (bundled with Git for Windows)
set "XZ="
if exist "C:\Program Files\Git\mingw64\bin\xz.exe" set "XZ=C:\Program Files\Git\mingw64\bin\xz.exe"
if exist "C:\Program Files\Git\usr\bin\xz.exe"     set "XZ=C:\Program Files\Git\usr\bin\xz.exe"

pushd "%ASSETS%"

rem helper: download and verify geo db (with 24h cache = file mtime)
rem %1 = name (geoip/geosite), %2 = repo
goto :skip_download_helper
:download_geo
set "DN=%~1"
set "REPO=%~2"
set "LOCAL_VER="
if exist "%DN%.version.txt" set /p LOCAL_VER=<"%DN%.version.txt"

rem 24h cache: check .db.xz file modification time
if exist "%DN%.db.xz" (
    powershell -NoProfile -Command "if (((Get-Item '%DN%.db.xz').LastWriteTime) -lt (Get-Date).AddHours(-24)) { exit 1 } else { exit 0 }"
    if not errorlevel 1 (
        echo [SKIP] %DN%.db.xz ^(!LOCAL_VER!^) - modified ^<24h ago
        goto :eof
    )
)

rem fetch latest version from GitHub API
set "REMOTE_VER="
for /f "delims=" %%v in ('powershell -NoProfile -Command "try { (Invoke-RestMethod 'https://api.github.com/repos/%REPO%/releases/latest' -TimeoutSec 30).tag_name } catch { '' }" 2^>nul') do set "REMOTE_VER=%%v"
if not defined REMOTE_VER (
    if defined LOCAL_VER if exist "%DN%.db.xz" (
        echo [SKIP] %DN%.db.xz ^(!LOCAL_VER!^) - using cached ^(API unreachable^)
        goto :eof
    )
    echo [WARN] %DN% cannot get version ^(check internet^)
    goto :eof
)
if "!LOCAL_VER!"=="!REMOTE_VER!" if exist "%DN%.db.xz" (
    powershell -NoProfile -Command "(Get-Item '%DN%.db.xz').LastWriteTime = Get-Date" 2>nul
    echo [SKIP] %DN%.db.xz ^(!REMOTE_VER!^) - up to date
    goto :eof
)

echo     new version: !REMOTE_VER! ^(local: !LOCAL_VER!^)
echo     downloading %DN%.db ...
del /q "%DN%.db" "%DN%.db.xz" "%DN%.db.sha256sum" 2>nul
powershell -NoProfile -Command "Invoke-WebRequest 'https://github.com/%REPO%/releases/download/!REMOTE_VER!/%DN%.db' -OutFile '%DN%.db' -TimeoutSec 120" >nul 2>nul
if not exist "%DN%.db" ( echo [WARN] %DN%.db download failed & goto :eof )

echo     downloading %DN%.db.sha256sum ...
powershell -NoProfile -Command "Invoke-WebRequest 'https://github.com/%REPO%/releases/download/!REMOTE_VER!/%DN%.db.sha256sum' -OutFile '%DN%.db.sha256sum' -TimeoutSec 30" >nul 2>nul
if exist "%DN%.db.sha256sum" (
    set /p "EXPECTED=" < "%DN%.db.sha256sum"
    for /f %%h in ("!EXPECTED!") do set "EXPECTED_HASH=%%h"
    certutil -hashfile "%DN%.db" SHA256 > "%TEMP_DIR%\%DN%.hash.tmp"
    set "ACTUAL_HASH="
    for /f "skip=1 delims=" %%l in ('type "%TEMP_DIR%\%DN%.hash.tmp"') do (
        if not defined ACTUAL_HASH (
            for %%t in (%%l) do if not defined ACTUAL_HASH set "ACTUAL_HASH=%%t"
        )
    )
    del /q "%TEMP_DIR%\%DN%.hash.tmp" 2>nul
    if /i "!ACTUAL_HASH!"=="!EXPECTED_HASH!" (
        echo     sha256 verified
    ) else (
        echo [FAIL] %DN%.db hash mismatch ^(expected: !EXPECTED_HASH! actual: !ACTUAL_HASH!^)
        del /q "%DN%.db" "%DN%.db.sha256sum" 2>nul
        goto :eof
    )
    del /q "%DN%.db.sha256sum" 2>nul
) else (
    echo [WARN] sha256sum not available, skipping verification
)

if defined XZ (
    echo     compressing with xz ...
    "%XZ%" -9 "%DN%.db" 2>nul
)
if exist "%DN%.db.xz" (
    del /q "%DN%.db" 2>nul
    echo !REMOTE_VER!> "%DN%.version.txt"
    echo [OK] %DN%.db.xz ^(!REMOTE_VER!^)
) else (
    if not defined XZ (
        echo [WARN] xz not found, keeping %DN%.db uncompressed
    )
)
goto :eof

:skip_download_helper

rem --- geoip ---
echo.
echo ^>^>^> sing-geoip ...
call :download_geo geoip SagerNet/sing-geoip

rem --- geosite ---
echo.
echo ^>^>^> sing-geosite ...
call :download_geo geosite SagerNet/sing-geosite

popd

rem ============================================================
rem 5. Build APK with Gradle
rem ============================================================
if "%NO_GRADLE%"=="1" goto :skip_gradle

echo.
echo ========================================
echo  Building APK (%BUILD_TYPE%)
echo ========================================

set "GRADLE_TASK=app:assembleOssRelease"
if /i "%BUILD_TYPE%"=="preview" set "GRADLE_TASK=app:assemblePreviewRelease"
if /i "%BUILD_TYPE%"=="play"    set "GRADLE_TASK=app:bundlePlayRelease"

echo.
echo ^>^>^> gradlew.bat %GRADLE_TASK% ...
pushd "%PROJECT_ROOT%"
call gradlew.bat %GRADLE_TASK%
if %ERRORLEVEL% neq 0 (
    popd
    echo [FAIL] Gradle build failed
    exit /b 1
)
popd
echo [OK] Gradle build succeeded
goto :done

:skip_gradle
echo [SKIP] Gradle build (--NoGradle)

:done
rem ============================================================
rem 6. Show output
rem ============================================================
echo.
echo ========================================
echo  Build finished!
echo ========================================

if "%BUILD_TYPE%"=="oss" if "%NO_GRADLE%"=="0" (
    if exist "%PROJECT_ROOT%\app\build\outputs\apk" (
        echo.
        echo APK outputs:
        for /r "%PROJECT_ROOT%\app\build\outputs\apk" %%f in (*.apk) do (
            echo   %%f
        )
    )
)

echo.
echo Usage:
echo   build.bat                     full build (libcore + gradle)
echo   build.bat -NoGradle           build libcore only
echo   build.bat -NoLibcore          rebuild APK only
echo   build.bat -Fast               skip gomobile init
echo   build.bat -BuildType preview  preview build
echo.

rem cleanup temp files
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"

pause
