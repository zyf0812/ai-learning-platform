@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
set PG_HOME=C:\Program Files\PostgreSQL\16
cd /d D:\面试项目\pgvector-src\pgvector-0.8.3
nmake /F Makefile.win
echo DONE
if errorlevel 1 echo FAILED
