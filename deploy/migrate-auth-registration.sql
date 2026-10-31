-- 自助注册与学生手机号认证（2026-10-31）
-- 已在旧版本数据库上执行一次即可；schema.sql 已同步给全新安装。

ALTER TABLE t_user
  ADD COLUMN phone_ciphertext VARCHAR(512) DEFAULT NULL COMMENT '手机号 AES-GCM 密文' AFTER subscribe_status,
  ADD COLUMN phone_hash CHAR(64) DEFAULT NULL COMMENT '手机号 HMAC，用于查重' AFTER phone_ciphertext,
  ADD COLUMN phone_last4 CHAR(4) DEFAULT NULL COMMENT '手机号后四位，用于脱敏展示' AFTER phone_hash,
  ADD COLUMN phone_verified_at DATETIME DEFAULT NULL COMMENT '手机号验证时间' AFTER phone_last4,
  ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT '密码重置后递增，用于撤销旧 JWT' AFTER status,
  ADD UNIQUE KEY uk_user_phone_hash (phone_hash);
