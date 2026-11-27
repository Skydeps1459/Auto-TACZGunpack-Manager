@echo off
REM ============================================================
REM  Auto TACZ Gunpack Manager - CLI dev entry
REM  Pure command-line workflow, no IDE required
REM  Target: Minecraft 1.20.1 + Forge 47.x + Java 17
REM ============================================================

setlocal

REM --- Locate JDK 17 (required by Minecraft 1.20.1) ---
if not defined JAVA_HOME (
    if exist "C:\Program Files\Zulu\zulu-17\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Zulu\zulu-17"
    ) else if exist "C:\Program Files\Java\jdk-17\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Java\jdk-17"
    ) else if exist "C:\Program Files\Eclipse Adoptium\jdk-17\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17"
    )
)

if not defined JAVA_HOME goto nojdk

set "PATH=%JAVA_HOME%\bin;%PATH%"

if "%~1"=="" goto help
if /i "%~1"=="client" goto client
if /i "%~1"=="server" goto server
if /i "%~1"=="build"  goto build
if /i "%~1"=="test"   goto test
if /i "%~1"=="data"   goto data
if /i "%~1"=="clean"  goto clean
if /i "%~1"=="tasks"  goto tasks
if /i "%~1"=="log"    goto log
if /i "%~1"=="env"    goto env
goto help

:client
echo [run] starting Minecraft dev client...
echo [tip] log file: run\logs\latest.log
call gradlew.bat runClient --console=plain
goto end

:server
echo [run] starting dev server...
echo [tip] pipe commands in, e.g.  dev.bat server ^< cmds.txt
call gradlew.bat runServer --console=plain
goto end

:build
echo [run] building mod (with unit tests)...
call gradlew.bat build --console=plain
echo.
echo [ok] output: build\libs\
dir /b build\libs\*.jar 2>nul
goto end

:test
echo [run] running unit tests...
call gradlew.bat test --console=plain
goto end

:data
echo [run] running data generation...
call gradlew.bat runData --console=plain
goto end

:clean
echo [run] cleaning build output...
call gradlew.bat clean --console=plain
echo [ok] done - run\ and dependency caches kept
goto end

:tasks
call gradlew.bat tasks --all --console=plain
goto end

:log
if exist "run\logs\latest.log" (
    type "run\logs\latest.log"
) else (
    echo [warn] no log yet - run "dev.bat client" first
)
goto end

:env
echo.
echo   JAVA_HOME = %JAVA_HOME%
"%JAVA_HOME%\bin\java" -version
echo.
call gradlew.bat --version --console=plain
goto end

:nojdk
echo [error] JDK 17 not found.
echo         Minecraft 1.20.1 requires Java 17 to compile and run.
echo         Install JDK 17 or set JAVA_HOME manually.
echo         Looked in: C:\Program Files\Zulu\zulu-17
goto end

:help
echo.
echo  Auto TACZ Gunpack Manager - CLI
echo.
echo  Usage: dev.bat ^<command^>
echo.
echo    build     build mod jar (runs tests) -> build\libs\
echo    test      run unit tests only
echo    client    start Minecraft dev client
echo    server    start dev server (accepts piped stdin)
echo    data      generate recipes / models / loot tables
echo    clean     clean build output
echo    tasks     list all gradle tasks
echo    log       print client log
echo    env       show JDK and Gradle versions
echo.
echo  First "client" run downloads ~1GB of assets.
echo.
goto end

:end
endlocal