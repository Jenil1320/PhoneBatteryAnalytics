@echo off
REM Record one battery sample and rebuild the dashboard. Safe to run on a
REM schedule (Windows Task Scheduler) or on demand. Requires the phone
REM connected over USB with USB debugging enabled.
cd /d "%~dp0"
py battery.py collect --report
