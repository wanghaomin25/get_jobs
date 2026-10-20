@echo off
setlocal
set "JAVA_HOME=%USERPROFILE%\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK21 not found at %JAVA_HOME%
    pause
    exit /b 1
)

echo [INFO] Starting Get Jobs... When ready, open http://localhost:6866
echo [INFO] Leave this window open while using it. Press Ctrl+C to stop.
call "%USERPROFILE%\gradle-9.7.1\bin\gradle.bat" bootRun
pause
