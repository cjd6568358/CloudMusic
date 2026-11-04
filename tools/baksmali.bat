@echo off
REM baksmali 3.0.10 wrapper: dis dexdump of dex/odex/oat
java -Xmx2g -jar "%~dp0baksmali-3.0.10.jar" %*
