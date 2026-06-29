@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
set PG_HOME=C:\Program Files\PostgreSQL\16
cd /d D:\pgvector-build
nmake /F Makefile.win
echo BUILD_DONE
