@echo off
echo first=[%~1]
echo second=[%~2]
echo third=[%~3]
if "%~4"=="fail" exit /b 9
exit /b 0
