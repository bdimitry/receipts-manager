@echo off
where mvn >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
  echo mvn is required but was not found in PATH. 1>&2
  exit /b 1
)
mvn %*
