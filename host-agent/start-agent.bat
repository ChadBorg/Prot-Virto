@echo off
title REDLINE agent
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" redline_agent.py %*
) else (
  python redline_agent.py %*
)
pause
