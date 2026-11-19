@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist out mkdir out

echo [1/2] 编译后端...
javac --add-modules jdk.httpserver -encoding UTF-8 -cp "lib/*" -d out src\Server.java
if errorlevel 1 (
    echo 编译失败：请确认已安装 JDK 11 以上版本。
    pause
    exit /b 1
)

echo [2/2] 启动服务（浏览器打开 http://localhost:8080）...
java --add-modules jdk.httpserver -cp "out;lib/*" Server
pause
