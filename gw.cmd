@echo off
rem Forward to gw.ps1 (gradle wrapper with Tencent mirror prefetch)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gw.ps1" %*
exit /b %ERRORLEVEL%
