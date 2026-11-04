@echo off
REM smali 3.0.10 wrapper: assemble smali back into dex
java -Xmx2g -jar "%~dp0smali-3.0.10.jar" %*
