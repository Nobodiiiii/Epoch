@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\update_all_instances.ps1" %*
set "EpochUpdateExit=%ERRORLEVEL%"
echo.
if "%EpochUpdateExit%"=="0" (
    echo Epoch update completed successfully.
) else (
    echo Epoch update failed. Read the error above and the update log.
)
pause
exit /b %EpochUpdateExit%
