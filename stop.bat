@echo off
echo Stopping all services...
taskkill /f /fi "WINDOWTITLE eq Backend" > nul 2>&1
taskkill /f /fi "WINDOWTITLE eq Frontend" > nul 2>&1
echo Done.
