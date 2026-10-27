@echo off
chcp 936 >nul
setlocal
title 生成批量建号 SQL（把名单变成 INSERT 语句）
cd /d "%~dp0"

REM ---------------------------------------------------------------------------
REM  把「学号,姓名,角色」名单转成带 bcrypt 哈希的 INSERT 语句。
REM  为什么必须用代码：t_user.password 存的是 bcrypt 加盐哈希，每个学号的哈希都
REM  不一样（盐随机），而 MySQL 没有 bcrypt 函数 —— SQL 里算不出来。
REM
REM  用法：gen-import-sql.bat 名单.csv [输出文件.sql]
REM  详细说明看同目录的 README-import.md。
REM ---------------------------------------------------------------------------

set JAR=C:\Users\joya\.m2\repository\org\mindrot\jbcrypt\0.4\jbcrypt-0.4.jar

if "%~1"=="" (
  echo 用法：gen-import-sql.bat 名单.csv [输出文件.sql]
  echo.
  echo   名单是一个 UTF-8 文本文件，一行一个账号，格式是「学号,姓名,角色」：
  echo       2023001001,张三,学生
  echo       2023002001,王五,干事
  echo.
  echo   第 4 列可以不填 —— 不填就自动用**学号后 6 位**当初始密码。
  echo   角色写中文（学生/干事/管理员）或英文（STUDENT/STAFF/ADMIN）都行。
  echo   格式示例见本目录的 users-example.csv。
  echo.
  pause
  exit /b 1
)

if not exist "%JAR%" (
  echo [错误] 找不到 jbcrypt 这个 jar：
  echo         %JAR%
  echo.
  echo         解决办法：在项目根目录先跑一次 "mvnw compile"，
  echo         让 Maven 把 jbcrypt 下载到本地仓库，然后再运行本脚本。
  echo.
  pause
  exit /b 1
)

if not exist "%~1" (
  echo [错误] 找不到名单文件：%~1
  echo         文件名里如果有空格，请用英文双引号把它包起来。
  echo.
  pause
  exit /b 1
)

REM 优先用 PATH 里的 java；没有就退回本项目用的那个 JDK
set JAVACMD=java
where java >nul 2>&1 || set JAVACMD=C:\Users\joya\.jdks\jdk-17.0.14+7\bin\java.exe

REM Java 11 以上可以直接运行单个 .java 文件，不用 javac、不用编译步骤。
REM 程序自己用 UTF-8 写 .sql 文件（不走屏幕重定向）—— 这样中文姓名才不会变乱码。
"%JAVACMD%" -cp "%JAR%" GenImportSql.java %*

echo.
pause
