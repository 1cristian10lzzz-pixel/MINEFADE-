@echo off
setlocal
set GRADLE_VERSION=9.6.0
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST_ROOT=%GRADLE_USER_HOME%\minefade-distributions
set DIST_DIR=%DIST_ROOT%\gradle-%GRADLE_VERSION%
set GRADLE_BIN=%DIST_DIR%\bin\gradle.bat
if exist "%GRADLE_BIN%" goto run_gradle
if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
set ZIP_FILE=%DIST_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_FILE%'"
if errorlevel 1 exit /b 2
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP_FILE%' '%DIST_ROOT%'"
if errorlevel 1 exit /b 2
del "%ZIP_FILE%"
:run_gradle
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
