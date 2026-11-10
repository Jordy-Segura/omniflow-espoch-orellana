@echo off
REM Publica una nueva version de OmniFlow en el backend. Los telefonos se actualizan solos.
REM Uso:  publicar-actualizacion.bat "Descripcion de los cambios"
setlocal
cd /d "%~dp0"
if exist "C:\Program Files\Android\Android Studio\jbr" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call "%~dp0gradlew.bat" publishApk -Pomniflow.release "-Pomniflow.notes=%~1" --console=plain
endlocal
