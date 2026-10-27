-- =============================================================
-- 第二课堂学时答疑工单系统 — 数据库建表脚本
-- 数据库：second_classroom (utf8mb4)
-- MySQL 8.0+
--
-- 说明（相对设计文档的两处 MySQL 必要修正）：
--   1. 每张表显式指定 ENGINE=InnoDB + DEFAULT CHARSET=utf8mb4
--      （只有 InnoDB 支持外键；utf8mb4 保证中文不乱码）
--   2. t_faq 的全文索引使用 WITH PARSER ngram，否则中文无法被检索
--
-- 业务字段 / 枚举 / 索引 均严格按《数据库设计最终版》原样保留。
-- 运行方式见文末。
-- =============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ===================== t_user =====================
CREATE TABLE t_user (
  user_id          VARCHAR(32)  NOT NULL,
  user_name        VARCHAR(50)  NOT NULL,
  user_type        ENUM('STUDENT','STAFF','ADMIN') NOT NULL,
  password         VARCHAR(255) NOT NULL COMMENT 'bcrypt 加密密码',
  open_id          VARCHAR(128) DEFAULT NULL COMMENT '微信公众号 OpenID（未来扩展）',
  subscribe_status ENUM('SUBSCRIBED','UNSUBSCRIBED') NOT NULL DEFAULT 'UNSUBSCRIBED',
  status           ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  last_login_time  DATETIME     DEFAULT NULL,
  PRIMARY KEY (user_id),
  KEY idx_user_type (user_type),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_work_order =====================
CREATE TABLE t_work_order (
  work_order_id        VARCHAR(32) NOT NULL COMMENT 'WO+YYYYMMDD+4位序号',
  user_id              VARCHAR(32) NOT NULL COMMENT '提交学生ID',
  user_name            VARCHAR(50) NOT NULL COMMENT '提交学生姓名快照',
  category             ENUM('学时录入','审核驳回','活动咨询','规则疑问','其他') NOT NULL,
  content              TEXT        NOT NULL COMMENT '首次问题描述（≤500字）',
  status               ENUM('PENDING','PROCESSING','REPLIED','CLOSED','MERGED') NOT NULL DEFAULT 'PENDING',
  handler_id           VARCHAR(32) DEFAULT NULL COMMENT '当前处理干事ID',
  handler_name         VARCHAR(50) DEFAULT NULL COMMENT '处理干事姓名快照',
  main_order_id        VARCHAR(32) DEFAULT NULL COMMENT '合并后主工单ID',
  source_order_id       VARCHAR(32) DEFAULT NULL COMMENT '再次咨询来源工单ID',
  relation_type        ENUM('RE_CONSULTATION') DEFAULT NULL COMMENT '关联类型',
  is_repeat            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否重复工单',
  duplicate_of_order_id VARCHAR(32) DEFAULT NULL COMMENT '对应原重复工单ID',
  is_flagged           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否命中敏感词',
  request_id           VARCHAR(64)  DEFAULT NULL COMMENT '幂等请求ID（UUID）',
  student_unread_count INT          NOT NULL DEFAULT 0 COMMENT '学生未读数（统计字段）',
  staff_unread_count   INT          NOT NULL DEFAULT 0 COMMENT '负责人未读数（统计字段）',
  version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  accept_time          DATETIME     DEFAULT NULL,
  reply_time           DATETIME     DEFAULT NULL,
  finish_time          DATETIME     DEFAULT NULL,
  update_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (work_order_id),
  UNIQUE KEY uk_request_id (request_id),
  KEY idx_user_status (user_id, status),
  KEY idx_handler_status (handler_id, status),
  KEY idx_status_create (status, create_time),
  KEY idx_create_time (create_time),
  KEY idx_category (category),
  KEY idx_source_order (source_order_id),
  KEY idx_main_order (main_order_id),
  CONSTRAINT FK_work_order_user    FOREIGN KEY (user_id) REFERENCES t_user (user_id),
  CONSTRAINT FK_work_order_handler FOREIGN KEY (handler_id) REFERENCES t_user (user_id),
  CONSTRAINT FK_work_order_main    FOREIGN KEY (main_order_id) REFERENCES t_work_order (work_order_id),
  CONSTRAINT FK_work_order_source  FOREIGN KEY (source_order_id) REFERENCES t_work_order (work_order_id),
  CONSTRAINT FK_work_order_dup     FOREIGN KEY (duplicate_of_order_id) REFERENCES t_work_order (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_work_order_message =====================
CREATE TABLE t_work_order_message (
  message_id  VARCHAR(32) NOT NULL COMMENT 'MSG+YYYYMMDD+6位序号',
  work_order_id VARCHAR(32) NOT NULL,
  sender_id   VARCHAR(32) DEFAULT NULL COMMENT '发送人ID；SYSTEM 为 NULL',
  sender_type ENUM('STUDENT','STAFF','SYSTEM') NOT NULL,
  sender_name VARCHAR(50) NOT NULL COMMENT '发送人姓名快照；SYSTEM 为 "系统"',
  msg_type    ENUM('QUESTION','REPLY','FOLLOW_UP','SYSTEM') NOT NULL,
  content     TEXT        NOT NULL COMMENT '消息内容（≤1000字）',
  request_id  VARCHAR(64) DEFAULT NULL COMMENT '幂等请求ID（REPLY/FOLLOW_UP 有值）',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (message_id),
  UNIQUE KEY uk_request_id (request_id),
  KEY idx_work_order (work_order_id, create_time),
  KEY idx_sender (sender_id),
  CONSTRAINT FK_msg_order  FOREIGN KEY (work_order_id) REFERENCES t_work_order (work_order_id),
  CONSTRAINT FK_msg_sender FOREIGN KEY (sender_id) REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_message_read =====================
CREATE TABLE t_message_read (
  read_id     BIGINT      NOT NULL AUTO_INCREMENT,
  message_id  VARCHAR(32) NOT NULL,
  user_id     VARCHAR(32) NOT NULL,
  read_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (read_id),
  UNIQUE KEY uk_msg_user (message_id, user_id),
  KEY idx_user (user_id),
  CONSTRAINT FK_read_message FOREIGN KEY (message_id) REFERENCES t_work_order_message (message_id),
  CONSTRAINT FK_read_user    FOREIGN KEY (user_id) REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_attachment（多态引用，无外键） =====================
CREATE TABLE t_attachment (
  attachment_id  VARCHAR(32) NOT NULL COMMENT 'ATT+YYYYMMDD+6位序号',
  upload_user_id VARCHAR(32) NOT NULL,
  target_id      VARCHAR(32) DEFAULT NULL COMMENT '绑定目标ID（work_order_id 或 message_id）',
  target_type    ENUM('WORK_ORDER','MESSAGE') DEFAULT NULL COMMENT '绑定目标类型；TEMP 为 NULL',
  status         ENUM('TEMP','BOUND') NOT NULL DEFAULT 'TEMP',
  file_name      VARCHAR(255) NOT NULL,
  file_url       VARCHAR(512) NOT NULL COMMENT 'OSS URL',
  file_type      VARCHAR(50)  NOT NULL COMMENT 'MIME type',
  file_size      BIGINT       NOT NULL COMMENT '单位 Byte',
  sort_num       INT          NOT NULL DEFAULT 0,
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  bind_time      DATETIME     DEFAULT NULL,
  PRIMARY KEY (attachment_id),
  KEY idx_target (target_type, target_id),
  KEY idx_status_create (status, create_time),
  KEY idx_upload_user (upload_user_id),
  CONSTRAINT FK_attach_user FOREIGN KEY (upload_user_id) REFERENCES t_user (user_id)
  -- 注意：target_id 为多态引用，不建外键（按约定查询必须同时指定 target_type + target_id）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_faq =====================
CREATE TABLE t_faq (
  faq_id       VARCHAR(16) NOT NULL COMMENT 'FAQ+6位序号',
  category     ENUM('学时录入','审核驳回','活动咨询','规则疑问','其他') NOT NULL,
  question     VARCHAR(200) NOT NULL COMMENT '问题标题（≤50字）',
  answer       TEXT         NOT NULL COMMENT '标准答案，支持富文本',
  keywords     VARCHAR(500) DEFAULT NULL COMMENT '匹配关键词，逗号分隔',
  sort_num     INT          NOT NULL DEFAULT 0,
  status       ENUM('PUBLISHED','UNPUBLISHED') NOT NULL DEFAULT 'PUBLISHED',
  view_count   INT          NOT NULL DEFAULT 0,
  creator_id   VARCHAR(32)  NOT NULL,
  creator_name VARCHAR(50)  NOT NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (faq_id),
  KEY idx_category_status (category, status),
  KEY idx_status_sort (status, sort_num),
  FULLTEXT KEY ft_keywords (question, keywords) WITH PARSER ngram,
  CONSTRAINT FK_faq_creator FOREIGN KEY (creator_id) REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_announcement =====================
CREATE TABLE t_announcement (
  notice_id     VARCHAR(16) NOT NULL COMMENT 'NOTICE+6位序号',
  title         VARCHAR(100) NOT NULL COMMENT '公告标题（≤30字）',
  content       TEXT         NOT NULL COMMENT '公告内容，支持富文本',
  sort_num      INT          NOT NULL DEFAULT 0,
  status        ENUM('ACTIVE','ARCHIVED') NOT NULL DEFAULT 'ACTIVE',
  expire_time   DATETIME     DEFAULT NULL,
  publisher_id  VARCHAR(32)  NOT NULL,
  publisher_name VARCHAR(50) NOT NULL,
  publish_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (notice_id),
  KEY idx_status_sort (status, sort_num, publish_time),
  CONSTRAINT FK_ann_publisher FOREIGN KEY (publisher_id) REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_merge_relation =====================
CREATE TABLE t_merge_relation (
  merge_id      VARCHAR(32) NOT NULL COMMENT 'MRG+YYYYMMDD+4位序号',
  main_order_id VARCHAR(32) NOT NULL,
  sub_order_id  VARCHAR(32) NOT NULL,
  operator_id   VARCHAR(32) NOT NULL,
  operator_name VARCHAR(50) NOT NULL,
  merge_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (merge_id),
  UNIQUE KEY uk_sub_order (sub_order_id),
  KEY idx_main_order (main_order_id),
  CONSTRAINT FK_merge_main     FOREIGN KEY (main_order_id) REFERENCES t_work_order (work_order_id),
  CONSTRAINT FK_merge_sub      FOREIGN KEY (sub_order_id)  REFERENCES t_work_order (work_order_id),
  CONSTRAINT FK_merge_operator FOREIGN KEY (operator_id)   REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_status_log =====================
CREATE TABLE t_status_log (
  log_id        VARCHAR(32) NOT NULL COMMENT 'SLOG+YYYYMMDD+6位序号',
  work_order_id VARCHAR(32) NOT NULL,
  before_status ENUM('PENDING','PROCESSING','REPLIED','CLOSED','MERGED') DEFAULT NULL,
  after_status  ENUM('PENDING','PROCESSING','REPLIED','CLOSED','MERGED') NOT NULL,
  action        ENUM('SUBMIT','ACCEPT','REPLY','FOLLOW_UP','CONFIRM','MANUAL_CLOSE','ADMIN_CLOSE','RETURN','MERGE','TRANSFER') NOT NULL,
  operator_id   VARCHAR(32) NOT NULL,
  operator_name VARCHAR(50) NOT NULL,
  operator_role ENUM('STUDENT','STAFF','ADMIN') NOT NULL,
  operation_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  remark        VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (log_id),
  KEY idx_work_order (work_order_id, operation_time),
  KEY idx_action (action),
  CONSTRAINT FK_slog_order    FOREIGN KEY (work_order_id) REFERENCES t_work_order (work_order_id),
  CONSTRAINT FK_slog_operator FOREIGN KEY (operator_id)   REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_operation_log =====================
CREATE TABLE t_operation_log (
  log_id            VARCHAR(32) NOT NULL COMMENT 'OLOG+YYYYMMDD+6位序号',
  user_id           VARCHAR(32) NOT NULL,
  user_name         VARCHAR(50) NOT NULL,
  user_role         ENUM('STUDENT','STAFF','ADMIN') NOT NULL,
  operation_type    VARCHAR(50) NOT NULL,
  target_type       VARCHAR(32) NOT NULL,
  target_id         VARCHAR(64) NOT NULL,
  operation_content TEXT        DEFAULT NULL,
  ip_address        VARCHAR(45) DEFAULT NULL COMMENT '支持 IPv6',
  operation_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (log_id),
  KEY idx_user_time (user_id, operation_time),
  KEY idx_target (target_type, target_id),
  KEY idx_op_type (operation_type, operation_time),
  CONSTRAINT FK_olog_user FOREIGN KEY (user_id) REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_quick_reply_template =====================
CREATE TABLE t_quick_reply_template (
  template_id  VARCHAR(16) NOT NULL COMMENT 'TPL+4位序号',
  category     ENUM('学时录入','审核驳回','活动咨询','规则疑问','其他') NOT NULL,
  title        VARCHAR(100) NOT NULL,
  content      TEXT         NOT NULL,
  sort_num     INT          NOT NULL DEFAULT 0,
  status       ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  creator_id   VARCHAR(32)  NOT NULL,
  creator_name VARCHAR(50)  NOT NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (template_id),
  KEY idx_category_status (category, status),
  KEY idx_status_sort (status, sort_num),
  CONSTRAINT FK_tpl_creator FOREIGN KEY (creator_id) REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_notification_log =====================
CREATE TABLE t_notification_log (
  notify_id    VARCHAR(32) NOT NULL COMMENT 'NOT+YYYYMMDD+6位序号',
  work_order_id VARCHAR(32) DEFAULT NULL,
  user_id      VARCHAR(32) NOT NULL,
  type         ENUM('NEW_TICKET','ACCEPT','REPLY','FOLLOW_UP','CLOSE','MERGE','PENDING_REMIND','EXCEPTION') NOT NULL,
  title        VARCHAR(200) NOT NULL,
  content      TEXT         DEFAULT NULL,
  channel      ENUM('PLATFORM','BROWSER','WECHAT') NOT NULL,
  priority     ENUM('P0','P1','P2') NOT NULL DEFAULT 'P1',
  target_type  ENUM('WORK_ORDER','FAQ','ANNOUNCEMENT','USER') DEFAULT NULL,
  target_id    VARCHAR(64) DEFAULT NULL,
  status       ENUM('PENDING','SUCCESS','FAILED') NOT NULL DEFAULT 'PENDING',
  is_read      TINYINT(1)   NOT NULL DEFAULT 0,
  read_time    DATETIME     DEFAULT NULL,
  retry_count  TINYINT      NOT NULL DEFAULT 0,
  fail_reason  VARCHAR(500) DEFAULT NULL,
  send_time    DATETIME     DEFAULT NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (notify_id),
  KEY idx_user_read (user_id, is_read),
  KEY idx_user_create (user_id, create_time),
  KEY idx_status (status),
  KEY idx_work_order (work_order_id),
  CONSTRAINT FK_notify_user  FOREIGN KEY (user_id) REFERENCES t_user (user_id),
  CONSTRAINT FK_notify_order FOREIGN KEY (work_order_id) REFERENCES t_work_order (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===================== t_sensitive_word =====================
CREATE TABLE t_sensitive_word (
  word_id      INT         NOT NULL AUTO_INCREMENT,
  word         VARCHAR(100) NOT NULL,
  status       ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  creator_id   VARCHAR(32)  NOT NULL,
  creator_name VARCHAR(50)  NOT NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (word_id),
  UNIQUE KEY uk_word (word),
  KEY idx_status (status),
  CONSTRAINT FK_sword_creator FOREIGN KEY (creator_id) REFERENCES t_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================
-- 运行方式（二选一）：
--
-- 方式 A · MySQL Workbench（最省事，推荐新手）：
--   1. 连上 localhost 的 second_classroom 库
--   2. 新建查询选项卡，把整个文件内容粘进去
--   3. 点闪电图标执行
--
-- 方式 B · 命令行 mysql 客户端：
--   "D:\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p second_classroom
--   进入后执行：  source D:/basic codes/second-classroom/schema.sql;
--   （路径含空格，source 用正斜杠且整体加引号）
--
-- 验证：
--   USE second_classroom;
--   SHOW TABLES;   -- 应看到 13 张表
-- =============================================================
