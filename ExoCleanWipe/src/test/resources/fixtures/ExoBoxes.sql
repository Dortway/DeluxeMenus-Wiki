-- Migrations.java
CREATE TABLE IF NOT EXISTS balances ( player_uuid TEXT NOT NULL, box_id TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0 CHECK (amount >= 0), PRIMARY KEY (player_uuid, box_id) );
-- Migrations.java
CREATE TABLE IF NOT EXISTS preferences ( player_uuid TEXT PRIMARY KEY, sounds INTEGER NOT NULL DEFAULT 1, animations INTEGER NOT NULL DEFAULT 1 );
-- Migrations.java
CREATE TABLE IF NOT EXISTS openings ( id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, box_id TEXT NOT NULL, source TEXT NOT NULL, quantity INTEGER NOT NULL, roll_count INTEGER NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL, delivered_at INTEGER );
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_openings_player_state ON openings (player_uuid, state);
-- Migrations.java
CREATE TABLE IF NOT EXISTS opening_rewards ( opening_id TEXT NOT NULL REFERENCES openings (id) ON DELETE CASCADE, seq INTEGER NOT NULL, reward_id TEXT NOT NULL, item BLOB NOT NULL, amount INTEGER NOT NULL, count INTEGER NOT NULL, delivered INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (opening_id, seq) );
-- Migrations.java
CREATE TABLE IF NOT EXISTS claims ( id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, item BLOB NOT NULL, amount INTEGER NOT NULL CHECK (amount > 0), reason TEXT NOT NULL, created_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_claims_player ON claims (player_uuid);
-- Migrations.java
CREATE TABLE IF NOT EXISTS audit ( id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, player_uuid TEXT, action TEXT NOT NULL, details TEXT );
-- Migrations.java
ALTER TABLE opening_rewards ADD COLUMN reward_type TEXT NOT NULL DEFAULT 'ITEM';
-- Migrations.java
ALTER TABLE opening_rewards ADD COLUMN commands TEXT;
-- Migrations.java
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
