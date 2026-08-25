@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.12
set PATH=%JAVA_HOME%\bin;%PATH%
set ANDROID_HOME=C:\Users\xiaod\AppData\Local\Android\sdk
set GRADLE_HOME=C:\Users\xiaod\AppData\Local\gradle\gradle-8.7
cd /d C:\Users\xiaod\Desktop\hermes\QQReplyApp
"%GRADLE_HOME%\bin\gradle.bat" assembleDebug --no-daemon 2>&1
