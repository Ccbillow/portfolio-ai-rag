-- ================================================================
-- RAG Knowledge Assistant — Database Schema
-- Auto-executed by MySQL Docker container on first boot.
-- File location: docker/mysql/init/01-schema.sql
-- ================================================================

CREATE DATABASE IF NOT EXISTS rag_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE rag_db;

-- ----------------------------------------------------------------
-- sys_user — authentication and authorization
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    username     VARCHAR(50)  NOT NULL COMMENT 'Unique login username',
    password     VARCHAR(100) NOT NULL COMMENT 'BCrypt hashed password',
    role         VARCHAR(30)  NOT NULL COMMENT 'ROLE_ADMIN or ROLE_INTERVIEWER',
    display_name VARCHAR(100) DEFAULT NULL COMMENT 'Name shown in chat',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Logical delete flag',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System users';

-- ----------------------------------------------------------------
-- rag_document — uploaded knowledge base files
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_document (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    file_name     VARCHAR(255)  NOT NULL COMMENT 'Original filename',
    file_type     VARCHAR(100)  DEFAULT NULL COMMENT 'MIME type',
    file_size     BIGINT        DEFAULT NULL COMMENT 'Size in bytes',
    category      VARCHAR(50)   NOT NULL COMMENT 'PROJECT_EXPERIENCE | STUDY_NOTES | CODE_SAMPLE',
    chunk_count   INT           DEFAULT 0 COMMENT 'Number of vector chunks stored in Qdrant',
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | PROCESSING | COMPLETED | FAILED',
    error_message TEXT          DEFAULT NULL COMMENT 'Error detail if FAILED',
    task_id       VARCHAR(36)   NOT NULL COMMENT 'Async task UUID for progress polling',
    uploaded_by   BIGINT        DEFAULT NULL COMMENT 'FK to sys_user.id',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_category (category),
    INDEX idx_status   (status),
    INDEX idx_task_id  (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge base documents';

-- ----------------------------------------------------------------
-- rag_chat_history — every Q&A pair for audit and analytics
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_chat_history (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    session_id       VARCHAR(36)   NOT NULL COMMENT 'Browser session UUID',
    user_id          BIGINT        DEFAULT NULL COMMENT 'FK to sys_user.id (null if anonymous)',
    question         TEXT          NOT NULL COMMENT 'User question text',
    answer           LONGTEXT      NOT NULL COMMENT 'LLM generated answer',
    source_doc_ids   VARCHAR(500)  DEFAULT NULL COMMENT 'Comma-separated document IDs used',
    retrieval_scores VARCHAR(500)  DEFAULT NULL COMMENT 'JSON array of cosine similarity scores',
    model_used       VARCHAR(100)  DEFAULT NULL COMMENT 'LLM model identifier',
    latency_ms       BIGINT        DEFAULT NULL COMMENT 'End-to-end latency in ms',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_session    (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chat Q&A history';

-- ----------------------------------------------------------------
-- Seed data — default admin account
-- Password: Admin@123 (BCrypt hash — change immediately in prod!)
-- ----------------------------------------------------------------
INSERT IGNORE INTO sys_user (username, password, role, display_name, enabled)
VALUES (
    'admin',
    '$2a$10$tP/4FjvJ6xsSL37.ROxkquu7ar/EWNySDFKTKcThocZtItDDeTFgS',
    'ROLE_ADMIN',
    'Admin',
    1
);