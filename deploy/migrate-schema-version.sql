-- =============================================================
-- 数据库迁移版本记录表（2026-10-31）
--
-- 解决的问题：只有 deploy/migrate-*.sql 这些文件，回答不了
--   「这个库到底执行过哪些迁移」——文件存在 ≠ 执行过。
-- 2026-10-31 要判断「注册功能上线了吗」，只能探接口、数表字段去猜。
--
-- 用法：
--   MYSQL_PWD=*** mysql -u root second_classroom < deploy/migrate-schema-version.sql
--   或：source deploy/migrate-schema-version.sql;
--
-- 幂等：可以重复执行。建表用 IF NOT EXISTS，插入用 INSERT IGNORE。
--
-- ⚠️ 这个脚本对「已迁移」和「未迁移」的库都适用：
--    baseline 那条无条件写；
--    auth-registration 那条**先检查库里有没有 token_version 列**，
--    有才写 —— 所以线上（没迁移）跑完只会多一条 baseline，不会假装迁移过。
-- =============================================================

CREATE TABLE IF NOT EXISTS t_schema_version (
  version    VARCHAR(64)  NOT NULL COMMENT '版本标识，如 2026-10-31-auth-registration',
  applied_at DATETIME     NOT NULL COMMENT '执行时间',
  applied_by VARCHAR(64)  DEFAULT NULL COMMENT '执行人 / 主机名',
  note       VARCHAR(255) DEFAULT NULL COMMENT '这次迁移做了什么',
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------- 1. 基线 ----------
-- 表示「开始使用版本记录之前，这个库已经是什么状态」。
-- 时间往前挪 1 分钟，让它在时间线上排在真正的迁移之前 —— 顺序反了将来排查会误导。
INSERT IGNORE INTO t_schema_version (version, applied_at, applied_by, note) VALUES
  ('baseline-before-auth-registration',
   DATE_SUB(NOW(), INTERVAL 1 MINUTE),
   USER(),
   '基线：启用版本记录前的库状态（t_user 尚无 phone_* 与 token_version）');


-- ---------- 2. 注册认证迁移（按实际表结构判断，不盲目写） ----------
-- 这段是"自证"的：只有库里真的有 token_version 列，才认为这次迁移执行过。
-- 线上现在没有这列，所以跑完只会有 baseline，不会留下假的迁移记录。
INSERT IGNORE INTO t_schema_version (version, applied_at, applied_by, note)
SELECT '2026-10-31-auth-registration',
       NOW(),
       USER(),
       't_user 增加 phone_ciphertext / phone_hash / phone_last4 / phone_verified_at / token_version，并加唯一索引 uk_user_phone_hash'
FROM DUAL
WHERE EXISTS (
  SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 't_user'
    AND COLUMN_NAME  = 'token_version'
);


-- ---------- 3. 看结果 ----------
SELECT version, applied_at, applied_by, note
FROM t_schema_version
ORDER BY applied_at;
