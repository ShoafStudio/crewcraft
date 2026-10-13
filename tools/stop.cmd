@echo off
rem Stops what tools\launch.ps1 started: same arguments as tools\stop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
exit /b %ERRORLEVEL%
