-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_players (uuid VARCHAR(36) PRIMARY KEY,name VARCHAR(16),last_zone VARCHAR(64),first_seen BIGINT NOT NULL DEFAULT 0,last_seen BIGINT NOT NULL DEFAULT 0,boss_kills INTEGER NOT NULL DEFAULT 0,crates_opened INTEGER NOT NULL DEFAULT 0);
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_player_kills (uuid VARCHAR(36) NOT NULL,tier INTEGER NOT NULL,kills BIGINT NOT NULL DEFAULT 0,PRIMARY KEY (uuid, tier));
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_backpacks (id VARCHAR(36) PRIMARY KEY,level INTEGER NOT NULL,owner VARCHAR(36),contents BLOB,version BIGINT NOT NULL DEFAULT 0,invalidated INTEGER NOT NULL DEFAULT 0,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL);
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_backpack_snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT,backpack_id VARCHAR(36) NOT NULL,contents BLOB,reason VARCHAR(32),created_at BIGINT NOT NULL);
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_lz_backpack_snapshots_bp ON lz_backpack_snapshots (backpack_id);
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_pending_rewards (id VARCHAR(36) PRIMARY KEY,player VARCHAR(36) NOT NULL,source VARCHAR(64),payload TEXT NOT NULL,created_at BIGINT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0);
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_lz_pending_rewards_player ON lz_pending_rewards (player);
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_consumed_items (item_id VARCHAR(36) PRIMARY KEY,type VARCHAR(32) NOT NULL,player VARCHAR(36),consumed_at BIGINT NOT NULL);
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT,player VARCHAR(36) NOT NULL,kind VARCHAR(16) NOT NULL,amount DOUBLE NOT NULL,balance_after DOUBLE,reason VARCHAR(128),actor VARCHAR(64),created_at BIGINT NOT NULL);
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_lz_transactions_player ON lz_transactions (player);
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_crate_state (player VARCHAR(36) NOT NULL,crate VARCHAR(64) NOT NULL,pity INTEGER NOT NULL DEFAULT 0,opened INTEGER NOT NULL DEFAULT 0,last_open BIGINT NOT NULL DEFAULT 0,PRIMARY KEY (player, crate));
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_crate_reward_counts (player VARCHAR(36) NOT NULL,crate VARCHAR(64) NOT NULL,reward VARCHAR(64) NOT NULL,count INTEGER NOT NULL DEFAULT 0,PRIMARY KEY (player, crate, reward));
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_boss_arenas (arena VARCHAR(64) PRIMARY KEY,tokens INTEGER NOT NULL DEFAULT 0,active INTEGER NOT NULL DEFAULT 0,started_at BIGINT NOT NULL DEFAULT 0,updated_at BIGINT NOT NULL DEFAULT 0);
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT,category VARCHAR(32) NOT NULL,player VARCHAR(36),message VARCHAR(512) NOT NULL,created_at BIGINT NOT NULL);
-- Migrations.java
ALTER TABLE lz_players ADD COLUMN zone_time_ms BIGINT NOT NULL DEFAULT 0;
-- Migrations.java
ALTER TABLE lz_players ADD COLUMN zone_window_start BIGINT NOT NULL DEFAULT 0;
-- Migrations.java
CREATE TABLE IF NOT EXISTS lz_schema_version (version INTEGER PRIMARY KEY, description VARCHAR(128), applied_at BIGINT NOT NULL);
