@echo off
REM Inicia el servidor OmniFlow en esta PC para que los telefonos de la misma red Wi-Fi se conecten.
REM Uso: doble clic. Para apagarlo, cierra esta ventana o presiona Ctrl+C.
setlocal
cd /d "%~dp0"
title Servidor OmniFlow

if not exist ".venv\Scripts\python.exe" (
    echo No se encontro el entorno de Python ^(.venv^) en %cd%
    echo Crealo una vez con:  py -m venv .venv  y luego  .venv\Scripts\pip install -r requirements.txt
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   Servidor OmniFlow
echo   En la app usa esta direccion (boton "Servidor"):
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    for /f "tokens=* delims= " %%b in ("%%a") do echo      http://%%b:8000
)
echo   El telefono debe estar en la misma red Wi-Fi.
echo   Para apagarlo: cierra esta ventana o presiona Ctrl+C.
echo ============================================================
echo.

".venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000
pause
