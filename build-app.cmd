@echo off
setlocal
cd /d "%~dp0"
where mvn >nul 2>nul
if errorlevel 1 (
    echo Maven not found. Install Maven 3.9 and add its bin directory to PATH.
    pause
    exit /b 1
)

call mvn -Papp clean package %*
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
if "%BUILD_EXIT_CODE%"=="0" (
    echo.
    echo Build complete: "%CD%\target\dist\WuwaLogin"
    echo Double-click WuwaLogin.exe to start. Keep the entire WuwaLogin folder.
) else (
    echo.
    echo Build failed. Check the error above and run mvn -version ^(JDK 25 required^).
)
pause
exit /b %BUILD_EXIT_CODE%
