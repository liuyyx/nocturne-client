@echo off
rem ---------------------------------------------------------------------------
rem  nocturne launcher
rem
rem  Double-click this instead of the .jar: it does not depend on the Windows
rem  .jar file association (frequently unset or pointing at a JRE), and it
rem  prefers a JDK whose bin\javaw.exe exists.
rem
rem  Resolution order for the jar: newest nocturne-*.jar under
rem  dist\build\libs\, else newest nocturne*.jar next to this script.
rem  Resolution order for java: %JAVA_HOME% -> any JDK under the usual vendor
rem  roots (highest version wins) -> javaw on PATH.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
set "ROOT=%~dp0"

rem Pick the NEWEST jar: dir /o-d lists by last-write time, newest first, so the
rem first match wins. This stops an older client from shadowing a fresh one.
rem dist ships nocturne-<version>.jar, hence the wildcard instead of a fixed name.
set "JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%ROOT%dist\build\libs\nocturne-*.jar" 2^>nul') do (
    if not defined JAR set "JAR=%ROOT%dist\build\libs\%%F"
)
rem Fall back to a jar beside this script; likewise newest first.
for /f "delims=" %%F in ('dir /b /a-d /o-d "%ROOT%nocturne*.jar" 2^>nul') do (
    if not defined JAR set "JAR=%ROOT%%%F"
)
if not defined JAR (
    echo [nocturne] no jar found under "%ROOT%dist\build\libs\"
    echo [nocturne] build it first:  gradle :dist:distJar
    pause
    exit /b 1
)

rem Pick the HIGHEST JDK version: compare each candidate version and only replace
rem the current best when strictly greater, so enumeration order no longer decides.
rem Version rule matches LauncherWindow.cs ParseJdkVersion: the first run of
rem digits in the directory name is the major version
rem (jdk1.8.0_411 -> 1, jdk-21 -> 21), so a mixed install selects 21, not JDK 8.
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"

if not defined JAVA_EXE (
    set "BEST_VER=-1"
    for %%R in (
        "C:\Program Files\Microsoft"
        "C:\Program Files\Eclipse Adoptium"
        "C:\Program Files\Java"
        "C:\Program Files\Amazon Corretto"
        "C:\Program Files\Zulu"
    ) do (
        if exist "%%~fR" (
            for /d %%D in ("%%~fR\jdk*") do (
                if exist "%%~fD\bin\javaw.exe" (
                    call :firstver "%%~nxD" VER
                    if not defined VER set "VER=-1"
                    if !VER! gtr !BEST_VER! (
                        set "BEST_VER=!VER!"
                        set "JAVA_EXE=%%~fD\bin\javaw.exe"
                    )
                )
            )
        )
    )
)

if not defined JAVA_EXE set "JAVA_EXE=javaw.exe"

echo [nocturne] java: %JAVA_EXE%
echo [nocturne] jar : %JAR%
start "" "%JAVA_EXE%" -jar "%JAR%" %*
exit /b 0

rem ---------------------------------------------------------------------------
rem  Extract the first run of digits from a JDK directory name, matching
rem  LauncherWindow.cs ParseJdkVersion:
rem    jdk-21.0.12.8-hotspot -> 21, jdk1.8.0_411 -> 1, jdk-foo -> (empty -> -1).
rem  Usage: call :firstver <directory-name> <result-variable>
rem ---------------------------------------------------------------------------
:firstver
setlocal enabledelayedexpansion
set "_s=%~1"
set "_out="
:_fv_loop
if "!_s!"=="" goto :_fv_done
set "_c=!_s:~0,1!"
set "_s=!_s:~1!"
rem A digit yields no token under for /f delims=digits, so the body is skipped
rem and the char is appended; a non-digit ends the run once digits were found,
rem otherwise it is part of the "jdk"/"openjdk" prefix and is skipped.
for /f "delims=0123456789" %%c in ("!_c!") do (
    if defined _out goto :_fv_done
    goto :_fv_loop
)
set "_out=!_out!!_c!"
goto :_fv_loop
:_fv_done
endlocal & set "%~2=%_out%"
exit /b 0
