@echo off
setlocal

rem ================================================================
rem   XWiki Realtime Playwright load test launcher (Windows)
rem
rem   Usage: run.bat [config.json]   (default: config.json)
rem
rem   Output:
rem     - Full run output goes to logs\realtime_<timestamp>.log
rem       instead of the console
rem     - After the run, all slow (>=500ms) / severe (>=2000ms)
rem       inputs are summarized:
rem         logs\stats_<timestamp>.txt   text report (echoed to console)
rem         stats_<timestamp>.csv        all inputs (openable in Excel)
rem     - Exit code: 0=ok, 1=runtime error, 2=missing markers or
rem       severe input latency (>=2000ms)
rem   NOTE: keep this file ASCII-only. Non-ASCII text breaks cmd.exe
rem         parsing on GBK (Chinese) Windows.
rem ================================================================

rem Switch to UTF-8 codepage for the node output
chcp 65001 >nul

set CONFIG=%~1
if "%CONFIG%"=="" set CONFIG=config.json

rem Timestamp (PowerShell, avoids %date% locale issues)
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set TS=%%i
if not exist logs mkdir logs

set LOGFILE=logs\realtime_%TS%.log
set STATFILE=logs\stats_%TS%.txt

echo [%TS%] Starting load test, config: %CONFIG%
echo [%TS%] Full output will be written to: %LOGFILE%
echo.
echo Running the load test, please do not close this window...

rem Run the load test, redirect all output to the log file
node realtime-wysiwyg-load.js --config "%CONFIG%" > "%LOGFILE%" 2>&1
set EXITCODE=%ERRORLEVEL%
echo exit code = %EXITCODE% >> "%LOGFILE%"

echo.
echo ================================================================
echo  Load test finished, exit code = %EXITCODE%
echo    (0 = ok, 1 = runtime error, 2 = missing markers or severe input latency)
echo   Full log: %LOGFILE%
echo ================================================================

rem Read artifactsDir from the config (default: artifacts)
for /f %%a in ('node -e "var fs=require(''fs'');try{var j=JSON.parse(fs.readFileSync(process.argv[1],''utf8''));process.stdout.write(j.artifactsDir||''artifacts'')}catch(e){process.stdout.write(''artifacts'')}" "%CONFIG%"') do set ARTDIR=%%a

echo.
echo Generating statistics (all slow/severe inputs)...
node analyze.js "%ARTDIR%" > "%STATFILE%" 2>&1
set ANALYZE_EXIT=%ERRORLEVEL%

rem Echo the statistics report to the console
type "%STATFILE%"

echo.
echo Statistics report: %STATFILE%
echo CSV details: see stats_*.csv in the current directory
endlocal
