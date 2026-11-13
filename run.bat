@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo [LOI] Can JDK 17+ va javac trong PATH.
  pause
  exit /b 1
)
if not exist "out" mkdir "out"
javac -encoding UTF-8 -d out src\main\java\com\datrixdev\ftpclient\*.java
if errorlevel 1 (
  echo Bien dich that bai.
  pause
  exit /b 1
)
java -cp out com.datrixdev.ftpclient.FtpClientApp
if errorlevel 1 pause
