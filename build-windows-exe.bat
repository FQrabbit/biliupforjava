@echo off
setlocal EnableExtensions
title BiliUpForJava Windows EXE Build

pushd "%~dp0"
set "BILIUP_BUILD_EXIT_CODE=0"

echo.
echo ========================================
echo   BiliUpForJava Windows EXE Build
echo   Dual-EXE script version: 1
echo ========================================
echo Project: %CD%
echo Script: %~f0
echo.

if not exist "pom.xml" (
    echo [ERROR] pom.xml was not found. Keep this script in the project root.
    goto :failed
)

if not exist "app.rc" (
    echo [ERROR] app.rc was not found.
    goto :failed
)

if not exist "icon.ico" (
    echo [ERROR] icon.ico was not found.
    goto :failed
)

if /i "%~1"=="--verify-only" (
    set "VERIFY_ONLY=1"
    goto :verify_normal_artifacts
)

echo [1/5] Locating Visual Studio C++ tools...
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VS_PATH="

if exist "%VSWHERE%" (
    for /f "usebackq delims=" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_PATH=%%I"
)

if not defined VS_PATH (
    echo [ERROR] Visual Studio C++ x64 build tools were not found.
    echo Install the "Desktop development with C++" workload first.
    goto :failed
)

set "VCVARS=%VS_PATH%\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" (
    echo [ERROR] vcvars64.bat was not found under:
    echo         %VS_PATH%
    goto :failed
)

echo       Visual Studio: %VS_PATH%
call "%VCVARS%" >nul
if errorlevel 1 (
    echo [ERROR] Failed to initialize the Visual Studio x64 environment.
    goto :failed
)
set "BILIUP_BUILD_EXIT_CODE=0"

echo [2/5] Checking Maven and GraalVM...
for /f "tokens=2,*" %%A in ('reg query "HKCU\Environment" /v JAVA_HOME 2^>nul ^| findstr /i "JAVA_HOME"') do set "JAVA_HOME=%%B"
if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME is not configured.
    goto :failed
)

if not exist "%JAVA_HOME%\bin\native-image.cmd" (
    echo [ERROR] native-image was not found under JAVA_HOME:
    echo         %JAVA_HOME%
    goto :failed
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

where mvn >nul 2>&1
if errorlevel 1 (
    echo       Maven was not found in the inherited Path. Reloading the user Path...
    call :reload_user_path

    where mvn >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] mvn was not found. Install Maven and add it to Path.
        goto :failed
    )
)

where native-image >nul 2>&1
if errorlevel 1 (
    echo [ERROR] native-image was not found.
    echo Make sure JAVA_HOME points to GraalVM 17 and JAVA_HOME\bin is in Path.
    goto :failed
)

call mvn -version
if errorlevel 1 (
    echo [ERROR] Maven could not start.
    goto :failed
)

if /i "%~1"=="--env-check" (
    call native-image --version
    if errorlevel 1 goto :failed
    goto :finished
)

set "BUILD_COMPAT=0"
echo.
echo ========================================================
echo   OPTIONAL CPU COMPATIBILITY EXE
echo ========================================================
echo   Build biliupforjava-compat.exe for older CPUs without AVX/AVX2.
echo   It may be slightly slower than the normal EXE.
echo.
echo   ^>^>^> Choose within 8 seconds: [Y] Build  [N] Skip ^<^<^<
echo   No response defaults to [N] Skip.
echo.
choice /C YN /N /T 8 /D N /M "Build compatibility EXE"
if errorlevel 2 set "BUILD_COMPAT=0"
if errorlevel 1 if not errorlevel 2 set "BUILD_COMPAT=1"
if "%BUILD_COMPAT%"=="1" (
    echo          Compatibility EXE will be built after the normal EXE.
) else (
    echo          Compatibility EXE was skipped.
)

echo.
echo       Removing previous target directory...
if exist "target" rmdir /s /q "target"
if exist "target" (
    echo [ERROR] Failed to remove the previous target directory.
    echo         Close programs that may be using files under target and try again.
    goto :failed
)

set "NATIVE_BUILD_WORK=%TEMP%\biliupforjava-native-build"
if not exist "%NATIVE_BUILD_WORK%" mkdir "%NATIVE_BUILD_WORK%"
if defined JAVA_TOOL_OPTIONS (
    set "JAVA_TOOL_OPTIONS=-Drecord.work-path=%NATIVE_BUILD_WORK% %JAVA_TOOL_OPTIONS%"
) else (
    set "JAVA_TOOL_OPTIONS=-Drecord.work-path=%NATIVE_BUILD_WORK%"
)

echo [3/5] Compiling icon and version resources...
rc /nologo /fo app.res app.rc
if errorlevel 1 (
    echo [ERROR] Failed to compile app.rc. Check the rc.exe output above.
    goto :failed
)

if not exist "app.res" (
    echo [ERROR] app.res was not generated.
    goto :failed
)

echo [4/5] Building EXE with GraalVM Native Image...
echo       This usually takes a few minutes. High CPU and memory usage is normal.
call mvn clean -Pnative native:compile -DskipTests -Dnative.maven.plugin.version=0.9.28
set "NORMAL_BUILD_EXIT_CODE=%ERRORLEVEL%"
goto :verify_normal_artifacts

:build_complete
echo.
echo ========================================
if defined BUILD_WARNING (
    echo   BUILD COMPLETED WITH WARNINGS
) else (
    echo   BUILD SUCCESS
)
echo ========================================
echo EXE: %CD%\target\biliupforjava.exe
if "%BUILD_COMPAT%"=="1" (
    if defined COMPAT_BUILD_FAILED (
        echo COMPAT EXE: not generated. See warnings above.
    ) else (
        echo COMPAT EXE: %CD%\target\biliupforjava-compat.exe
    )
)
echo DLL: %CD%\target\*.dll
echo Keep the generated DLL files next to the EXE when publishing.
goto :finished

:failed
set "BILIUP_BUILD_EXIT_CODE=1"
echo.
echo ========================================
echo   BUILD FAILED
echo ========================================

:finished
popd
echo.
if /i not "%~1"=="--no-pause" if /i not "%~1"=="--verify-only" if /i not "%~1"=="--env-check" pause
exit /b %BILIUP_BUILD_EXIT_CODE%

:verify_normal_artifacts
if not defined VERIFY_ONLY echo [5/5] Checking build output...
set "MISSING_ARTIFACT="
for %%F in (
    "target\biliupforjava.exe"
    "target\awt.dll"
    "target\jaas.dll"
    "target\javaaccessbridge.dll"
    "target\javajpeg.dll"
    "target\jawt.dll"
    "target\lcms.dll"
    "target\w2k_lsa_auth.dll"
) do (
    if not exist %%F (
        echo [ERROR] Missing runtime artifact: %%~F
        set "MISSING_ARTIFACT=1"
    ) else if %%~zF EQU 0 (
        echo [ERROR] Empty runtime artifact: %%~F
        set "MISSING_ARTIFACT=1"
    )
)

if defined MISSING_ARTIFACT (
    if defined VERIFY_ONLY (
        echo [ERROR] One or more required build artifacts are missing.
    ) else if not "%NORMAL_BUILD_EXIT_CODE%"=="0" (
        echo [ERROR] Native Image build failed and the output is incomplete.
    ) else (
        echo [ERROR] One or more required build artifacts are missing.
    )
    goto :failed
)

if defined VERIFY_ONLY (
    echo Build artifacts are complete.
    goto :finished
)

if not "%NORMAL_BUILD_EXIT_CODE%"=="0" (
    echo [WARN] Native Image returned a non-zero exit code, but all runtime artifacts were generated.
    set "BUILD_WARNING=1"
)

if "%BUILD_COMPAT%"=="1" goto :build_compat
goto :build_complete

:build_compat
echo.
echo [OPTION] Building biliupforjava-compat.exe with CPU compatibility mode...
echo          Native Image target: -march=compatibility
call mvn -Pnative,native-compat native:compile -DskipTests -Dnative.maven.plugin.version=0.9.28 -Dnative.image.name=biliupforjava-compat
set "COMPAT_BUILD_EXIT_CODE=%ERRORLEVEL%"

if not exist "target\biliupforjava-compat.exe" goto :compat_build_missing
for %%F in ("target\biliupforjava-compat.exe") do if %%~zF EQU 0 goto :compat_build_missing

findstr /s /m /c:"-march=compatibility" "target\tmp\native-image-*.args" >nul 2>&1
if errorlevel 1 goto :compat_build_option_missing

if not "%COMPAT_BUILD_EXIT_CODE%"=="0" (
    echo [WARN] Compatibility Native Image returned a non-zero exit code, but the EXE was generated.
    set "BUILD_WARNING=1"
)
goto :build_complete

:compat_build_missing
echo [WARN] Compatibility EXE build failed. The normal EXE remains available.
set "BUILD_WARNING=1"
set "COMPAT_BUILD_FAILED=1"
goto :build_complete

:compat_build_option_missing
echo [WARN] Compatibility EXE was generated, but -march=compatibility was not passed to Native Image.
echo        Do not distribute this file as a CPU-compatible build.
set "BUILD_WARNING=1"
set "COMPAT_BUILD_FAILED=1"
goto :build_complete

:reload_user_path
set "USER_PATH="
for /f "tokens=2,*" %%A in ('reg query "HKCU\Environment" /v Path 2^>nul ^| findstr /i "Path"') do set "USER_PATH=%%B"
if defined USER_PATH set "PATH=%JAVA_HOME%\bin;%USER_PATH%;%PATH%"
exit /b 0
