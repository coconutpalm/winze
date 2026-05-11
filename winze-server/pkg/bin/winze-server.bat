@echo off
REM Launch the Winze Plan Server using the bundled JRE.
set DIR=%~dp0..
"%DIR%\jre\bin\java" --add-opens=java.base/java.nio=ALL-UNNAMED ^
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED ^
  --enable-native-access=ALL-UNNAMED ^
  -jar "%DIR%\lib\winze-server.jar" %*
