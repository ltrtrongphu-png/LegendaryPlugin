@echo off
setlocal
set MAVEN_VERSION=3.9.11
set WRAPPER_DIR=%~dp0.mvn\wrapper
set MAVEN_HOME=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%
set MAVEN_ARCHIVE=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%-bin.tar.gz
set MAVEN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.tar.gz

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
  if not exist "%MAVEN_ARCHIVE%" curl --fail --location --silent --show-error "%MAVEN_URL%" --output "%MAVEN_ARCHIVE%"
  tar -xzf "%MAVEN_ARCHIVE%" -C "%WRAPPER_DIR%"
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
