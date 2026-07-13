-- Password Reset Tokens Migration
-- Creates password_reset_tokens table for each resort
--
-- NOTE: As of July 2026 this migration is OBSOLETE — PasswordResetToken.java
-- now runs CREATE TABLE IF NOT EXISTS automatically on first use for each
-- resort, so no manual step is needed. Kept for schema reference only.
--
-- MULTI-TENANT PATTERN:
-- Each resort requires its own password_reset_tokens table with the resort name prefix.
-- Add CREATE TABLE statements for each production resort as needed.

-- Sample Resort (for testing)
CREATE TABLE IF NOT EXISTS Sample_password_reset_tokens (
    token VARCHAR(64) PRIMARY KEY COMMENT 'UUID-based secure random token',
    member_id VARCHAR(20) NOT NULL COMMENT 'Foreign key to Sample_roster.IDNumber',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Token creation time',
    expires_at TIMESTAMP NOT NULL COMMENT 'Token expiration time (created_at + 1 hour)',
    used BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'True if token has been used for reset',
    INDEX idx_member_id (member_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Password reset tokens for Sample resort';

-- Add similar CREATE TABLE statements for production resorts:
-- CREATE TABLE IF NOT EXISTS BreckenridgeNSP_password_reset_tokens (
--     token VARCHAR(64) PRIMARY KEY COMMENT 'UUID-based secure random token',
--     member_id VARCHAR(20) NOT NULL COMMENT 'Foreign key to BreckenridgeNSP_roster.IDNumber',
--     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Token creation time',
--     expires_at TIMESTAMP NOT NULL COMMENT 'Token expiration time (created_at + 1 hour)',
--     used BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'True if token has been used for reset',
--     INDEX idx_member_id (member_id),
--     INDEX idx_expires (expires_at)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Password reset tokens for BreckenridgeNSP resort';

-- CREATE TABLE IF NOT EXISTS Vail_password_reset_tokens (
--     token VARCHAR(64) PRIMARY KEY COMMENT 'UUID-based secure random token',
--     member_id VARCHAR(20) NOT NULL COMMENT 'Foreign key to Vail_roster.IDNumber',
--     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Token creation time',
--     expires_at TIMESTAMP NOT NULL COMMENT 'Token expiration time (created_at + 1 hour)',
--     used BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'True if token has been used for reset',
--     INDEX idx_member_id (member_id),
--     INDEX idx_expires (expires_at)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Password reset tokens for Vail resort';

-- Continue adding CREATE TABLE statements for each additional resort...
