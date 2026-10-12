-- Migrations.java
CREATE TABLE IF NOT EXISTS players ( uuid TEXT PRIMARY KEY, last_daily_spin INTEGER NULL, bonus_spins INTEGER NOT NULL DEFAULT 0 CHECK (bonus_spins >= 0), updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE TABLE IF NOT EXISTS spins ( id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, reward_id TEXT NOT NULL, reward_type TEXT NOT NULL, item_data BLOB NULL, item_amount INTEGER NOT NULL DEFAULT 0, commands TEXT NULL, display_data BLOB NULL, display_name TEXT NOT NULL, rarity_id TEXT NOT NULL, announcement TEXT NULL, detail TEXT NULL );
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_spins_player_status ON spins (player_uuid, status);
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_spins_status ON spins (status);
-- Migrations.java
CREATE TABLE IF NOT EXISTS pending_rewards ( id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, spin_id TEXT NULL, item_data BLOB NOT NULL, amount INTEGER NOT NULL CHECK (amount > 0), status TEXT NOT NULL, claim_op TEXT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_pending_player_status ON pending_rewards (player_uuid, status);
-- Migrations.java
CREATE TABLE IF NOT EXISTS audit_log ( id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, player_uuid TEXT NULL, ref TEXT NULL, event TEXT NOT NULL, detail TEXT NULL );
-- Migrations.java
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL, applied_at INTEGER NOT NULL);
