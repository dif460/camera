@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME set JAVA_HOME=D:\Compilation_environment\jdk8
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist out mkdir out
dir /s /b src\main\java\*.java > sources.txt
javac -encoding UTF-8 -source 1.8 -target 1.8 -cp "lib\*" -d out @sources.txt
if errorlevel 1 (
    echo.
    echo Build failed.
    del sources.txt
    exit /b 1
)
del sources.txt
echo Build OK -^> out\
