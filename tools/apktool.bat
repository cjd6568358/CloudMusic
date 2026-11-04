@echo off
REM apktool 2.10.0 wrapper - uses bundled framework dir, JDK from PATH
java -Xmx4g -jar "%~dp0apktool-2.10.0.jar" -p "%~dp0framework" %*
