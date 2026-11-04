@echo off
REM One-shot decode: resources + manifest only (fast, skips dex disassembly).
REM Usage: decode-apk.bat <path-to.apk> [output-dir]
setlocal
if "%~1"=="" (
  echo Usage: decode-apk.bat ^<apk^> [output-dir]
  exit /b 1
)
set OUT=%~2
if "%OUT%"=="" set OUT=%~n1-decoded
java -Xmx4g -jar "%~dp0apktool-2.10.0.jar" d -s -f -p "%~dp0framework" -o "%OUT%" "%~1"
endlocal
