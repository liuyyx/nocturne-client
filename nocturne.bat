@echo off
rem ---------------------------------------------------------------------------
rem  nocturne launcher
rem
rem  Double-click this instead of the .jar: it does not depend on the Windows
rem  .jar file association (frequently unset or pointing at a JRE), and it
rem  requires Java 11 or later for the GUI.
rem
rem Resolution order: newest nocturne-launcher-*.jar under dist\build\libs\,
rem then newest nocturne-launcher-*.jar next to this script.
rem Resolution order for Java 11+: JAVA_HOME -> usual vendor roots (highest
rem version wins) -> PATH.
rem ---------------------------------------------------------------------------
setlocal disabledelayedexpansion
set "ROOT=%~dp0"

rem Prefer the newest GUI launcher, never the Java 8 agent payload.
set "JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%ROOT%dist\build\libs\nocturne-launcher-*.jar" 2^>nul') do (
    if not defined JAR set "JAR=%ROOT%dist\build\libs\%%F"
)
for /f "delims=" %%F in ('dir /b /a-d /o-d "%ROOT%nocturne-launcher-*.jar" 2^>nul') do (
    if not defined JAR set "JAR=%ROOT%%%F"
)
if not defined JAR (
    echo [nocturne] GUI launcher not found: nocturne-launcher-*.jar
    echo [nocturne] Build it with: gradle :dist:launcherJar
    pause
    exit /b 1
)

set "JAVA_EXE="
set "BEST_VER=0"
if defined JAVA_HOME call :considerJava "%JAVA_HOME%\bin\javaw.exe"
if defined JAVA_EXE goto launch

rem Compare the JVM's actual version, not the name of its installation directory.
for %%R in (
    "%ProgramFiles%\Microsoft"
    "%ProgramFiles%\Eclipse Adoptium"
    "%ProgramFiles%\Java"
    "%ProgramFiles%\Amazon Corretto"
    "%ProgramFiles%\Zulu"
) do (
    for /d %%D in ("%%~fR\jdk*") do call :considerJava "%%~fD\bin\javaw.exe"
)
if defined JAVA_EXE goto launch

for /f "delims=" %%J in ('"%SystemRoot%\System32\where.exe" $PATH:javaw.exe 2^>nul') do (
    call :considerJava "%%J"
    if defined JAVA_EXE goto launch
)
echo [nocturne] Java 11 or newer is required for the GUI launcher.
echo [nocturne] Set JAVA_HOME to a supported JDK or add its bin directory to PATH.
pause
exit /b 1

:launch
echo [nocturne] java: "%JAVA_EXE%"
echo [nocturne] jar : "%JAR%"
start "" "%JAVA_EXE%" -jar "%JAR%" %*
exit /b 0

rem Check java.exe next to javaw.exe; use a small heap for the version probe.
:considerJava
if not exist "%~1" exit /b 0
if not exist "%~dp1java.exe" exit /b 0
set "JAVA_MAJOR=0"
for /f "tokens=1,2,3" %%A in ('""%~dp1java.exe" -Xms16m -Xmx64m -XshowSettings:properties -version 2>&1"') do (
    if "%%A"=="java.specification.version" if "%%B"=="=" (
        rem Legacy Java reports 1.x; its first component is below the minimum.
        for /f "tokens=1 delims=." %%V in ("%%C") do set "JAVA_MAJOR=%%V"
    )
)
for /f "delims=0123456789" %%V in ("%JAVA_MAJOR%") do exit /b 0
if %JAVA_MAJOR% lss 11 exit /b 0
if %JAVA_MAJOR% gtr %BEST_VER% (
    set "BEST_VER=%JAVA_MAJOR%"
    set "JAVA_EXE=%~1"
)
exit /b 0
