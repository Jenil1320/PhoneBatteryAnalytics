@echo off
REM Rebuild the dashboard from stored data and open it in your browser.
cd /d "%~dp0"
py battery.py report --open
