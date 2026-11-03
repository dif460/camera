@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME set JAVA_HOME=D:\Compilation_environment\jdk8
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist out\com\cama\Main.class call build.bat
if errorlevel 1 exit /b 1

java -Dfile.encoding=UTF-8 -cp "out;lib\*" com.cama.Main
