-- =============================================================
-- 第二课堂学时答疑工单系统 — 初始数据（上线专用）
--
-- 🔴 执行顺序：先 schema.sql（建表），再本文件（灌数据）。
-- 🔴 只能对**全新的空库**执行一次。重复执行会因主键冲突报错 —— 这是故意的，
--    免得把你后来改过的密码又悄悄改回初始值。
--
-- 执行（Windows 本机示例，路径含空格要加引号）：
--   "D:\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p ^
--       --default-character-set=utf8mb4 second_classroom < "D:\basic codes\second-classroom\deploy\seed.sql"
--
-- 为什么这里只有 1 个管理员：
--   本系统**没有注册功能，管理员也不能建号**（51 个接口里没有"新增用户"）。
--   账号是"预置"的 —— 这也是设计文档的模型：AuthService 里的 needChangePassword 就是按
--   "初始密码 = 学号后 6 位"设计的，管理员重置密码用的也是这个规则。
--   所以学生 / 干事的账号要靠**导入**，见同目录的 gen-import-sql.bat。
-- =============================================================

SET NAMES utf8mb4;

-- ===================== 唯一的初始账号：管理员 =====================
-- 学号 2023003001 / 密码 123456
--
-- password 列存的是 **bcrypt 加盐哈希**（`$2a$10$` 开头，60 字符），不是明文，也不是 MD5 ——
-- bcrypt 每次算出来的哈希都不一样（盐是随机的），但只要密码对就能验证通过。
--
-- ⚠️ 上线后请**第一时间**改掉这个密码：
--    登录 → 调 PUT /api/auth/password 传 {"oldPassword":"123456","newPassword":"你的新密码"}
--    （新密码要求 6-20 位，且同时含字母和数字 —— 这是 AuthService.changePassword 的校验）
INSERT INTO t_user (user_id, user_name, user_type, password, status)
VALUES ('2023003001', '系统管理员', 'ADMIN',
        '$2a$10$MYD8338x8UBTV6Uxpbm0z.H4NysTsPPeBxZUkWT1qiswrLErea8ie',
        'ACTIVE');

-- ===================== 自检 =====================
-- 执行完应该查到 1 行：
--   SELECT user_id, user_name, user_type, status FROM t_user;
-- 其余 12 张表应该全是空的（FAQ / 公告 / 模板 / 敏感词都由管理员上线后在后台自己建）：
--   SELECT table_name, table_rows FROM information_schema.TABLES
--    WHERE table_schema = 'second_classroom' ORDER BY table_name;
