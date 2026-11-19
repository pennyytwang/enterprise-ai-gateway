-- =====================================================================
-- AI Gateway 資料庫結構（aigateway schema）
-- =====================================================================

CREATE TABLE IF NOT EXISTS api_client (
    id INT AUTO_INCREMENT PRIMARY KEY,
    client_name VARCHAR(100) NOT NULL,
    api_key_hash CHAR(64) NOT NULL UNIQUE,
    role VARCHAR(20) NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN=系統管理者, USER=一般',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1=啟用 0=停用',
    rate_limit_daily INT NOT NULL DEFAULT 0 COMMENT '每日請求上限（0=無限）',
    rate_limit_per_min INT NOT NULL DEFAULT 0 COMMENT '每分鐘請求上限（0=無限）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Phase 5 migration: 既有表加限流欄位（continue-on-error=true 忽略已存在錯誤）
ALTER TABLE api_client ADD COLUMN rate_limit_daily INT NOT NULL DEFAULT 0;
ALTER TABLE api_client ADD COLUMN rate_limit_per_min INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    client_id INT NOT NULL,
    client_name VARCHAR(100),
    endpoint VARCHAR(100),
    model VARCHAR(100),
    stream TINYINT DEFAULT 0,
    prompt TEXT,
    response TEXT,
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    latency_ms BIGINT,
    status_code INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_request_id (request_id),
    KEY idx_client_id (client_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- CharPhone trusted user context（continue-on-error=true 忽略已存在錯誤）
ALTER TABLE audit_log ADD COLUMN app_user_id INT NULL AFTER client_name;
ALTER TABLE audit_log ADD COLUMN app_username VARCHAR(100) NULL AFTER app_user_id;
ALTER TABLE audit_log ADD COLUMN app_session_id VARCHAR(64) NULL AFTER app_username;
ALTER TABLE audit_log ADD INDEX idx_audit_app_user_id (app_user_id);

CREATE TABLE IF NOT EXISTS tool_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    client_id INT NOT NULL,
    client_name VARCHAR(100),
    endpoint VARCHAR(100),
    model VARCHAR(100),
    prompt TEXT COMMENT '工具參數 JSON',
    response TEXT COMMENT '工具結果摘要',
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    latency_ms BIGINT,
    status_code INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_request_id (request_id),
    KEY idx_client_id (client_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE tool_audit_log ADD COLUMN app_user_id INT NULL AFTER client_name;
ALTER TABLE tool_audit_log ADD COLUMN app_username VARCHAR(100) NULL AFTER app_user_id;
ALTER TABLE tool_audit_log ADD COLUMN app_session_id VARCHAR(64) NULL AFTER app_username;
ALTER TABLE tool_audit_log ADD INDEX idx_tool_audit_app_user_id (app_user_id);

CREATE TABLE IF NOT EXISTS gateway_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
