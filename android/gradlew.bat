@echo off
set VERSION=8.13
set SHA256=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
set BASE=%USERPROFILE%\.gradle\echo-bootstrap
set ZIP=%BASE%\gradle-%VERSION%-bin.zip
set DIST=%BASE%\gradle-%VERSION%
if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'"
  for /f "tokens=*" %%i in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower()"') do set ACTUAL=%%i
  if not "%ACTUAL%"=="%SHA256%" (echo Gradle checksum failed & exit /b 1)
  powershell -NoProfile -Command "Expand-Archive -Force '%ZIP%' '%BASE%'"
)
call "%DIST%\bin\gradle.bat" %*
