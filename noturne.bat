@echo off
rem ---------------------------------------------------------------------------
rem  noturne launcher
rem
rem  Double-click this instead of the .jar: it does not depend on the Windows
rem  .jar file association (frequently unset or pointing at a JRE), and it
rem  prefers a JDK whose bin\javaw.exe exists.
rem
rem  Resolution order: %JAVA_HOME% -> any JDK under the usual vendor roots
rem  (highest version wins) -> javaw on PATH.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
set "ROOT=%~dp0"

set "JAR="
for %%F in ("%ROOT%dist\build\libs\noturne-*.jar" "%ROOT%noturne.jar") do (
    if exist "%%~fF" set "JAR=%%~fF"
)
if not defined JAR (
    echo [noturne] no jar found under "%ROOT%dist\build\libs\"
    echo [noturne] build it first:  gradle :dist:distJar
    pause
    exit /b 1
)

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"

if not defined JAVA_EXE (
    for %%R in (
        "C:\Program Files\Microsoft"
        "C:\Program Files\Eclipse Adoptium"
        "C:\Program Files\Java"
        "C:\Program Files\Amazon Corretto"
        "C:\Program Files\Zulu"
    ) do (
        if exist "%%~fR" (
            for /d %%D in ("%%~fR\jdk*") do (
                if exist "%%~fD\bin\javaw.exe" set "JAVA_EXE=%%~fD\bin\javaw.exe"
            )
        )
    )
)

if not defined JAVA_EXE set "JAVA_EXE=javaw.exe"

echo [noturne] java: %JAVA_EXE%
echo [noturne] jar : %JAR%
start "" "%JAVA_EXE%" -jar "%JAR%" %*
