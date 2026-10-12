-- Migrations.java
CREATE TABLE market_items ( id TEXT PRIMARY KEY, item BLOB NOT NULL, rarity TEXT NOT NULL, base_price TEXT NOT NULL, weight REAL NOT NULL CHECK (weight >= 0), quantity INTEGER NOT NULL CHECK (quantity > 0), purchase_limit INTEGER NOT NULL CHECK (purchase_limit > 0), enabled INTEGER NOT NULL DEFAULT 1, version INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE TABLE rotations ( id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ends_at INTEGER NOT NULL, active INTEGER NOT NULL, reason TEXT );
-- Migrations.java
CREATE TABLE offers ( rotation_id INTEGER NOT NULL, player_uuid TEXT NOT NULL, slot INTEGER NOT NULL, item_id TEXT NOT NULL, item_version INTEGER NOT NULL, item BLOB NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), rarity TEXT NOT NULL, base_price TEXT NOT NULL, discount INTEGER NOT NULL, night_price TEXT NOT NULL, currency_id TEXT NOT NULL, scale INTEGER NOT NULL, purchase_limit INTEGER NOT NULL CHECK (purchase_limit > 0), purchased INTEGER NOT NULL DEFAULT 0 CHECK (purchased >= 0), revealed INTEGER NOT NULL DEFAULT 0, invalidated INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, PRIMARY KEY (rotation_id, player_uuid, slot) );
-- Migrations.java
CREATE INDEX idx_offers_item ON offers (rotation_id, item_id);
-- Migrations.java
CREATE TABLE transactions ( id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, rotation_id INTEGER NOT NULL, slot INTEGER NOT NULL, item_id TEXT NOT NULL, item BLOB NOT NULL, quantity INTEGER NOT NULL, currency_id TEXT NOT NULL, price TEXT NOT NULL, discount INTEGER NOT NULL, state TEXT NOT NULL, balance_before TEXT, note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX idx_tx_state ON transactions (state);
-- Migrations.java
CREATE INDEX idx_tx_player ON transactions (player_uuid, created_at);
-- Migrations.java
CREATE TABLE deliveries ( id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, transaction_id TEXT NOT NULL REFERENCES transactions (id), item BLOB NOT NULL, amount INTEGER NOT NULL CHECK (amount > 0), created_at INTEGER NOT NULL, delivered_at INTEGER, cancelled_at INTEGER );
-- Migrations.java
CREATE INDEX idx_deliveries_player ON deliveries (player_uuid, delivered_at, cancelled_at);
-- Migrations.java
CREATE INDEX idx_deliveries_tx ON deliveries (transaction_id);
-- Migrations.java
CREATE TABLE audit ( id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, player_uuid TEXT, tx_id TEXT, action TEXT NOT NULL, details TEXT );
-- Migrations.java
CREATE INDEX idx_audit_tx ON audit (tx_id);
-- Migrations.java
ALTER TABLE market_items ADD COLUMN commands TEXT;
-- Migrations.java
ALTER TABLE market_items ADD COLUMN free_slots INTEGER NOT NULL DEFAULT 0;
-- Migrations.java
ALTER TABLE offers ADD COLUMN commands TEXT;
-- Migrations.java
ALTER TABLE offers ADD COLUMN free_slots INTEGER NOT NULL DEFAULT 0;
-- Migrations.java
ALTER TABLE transactions ADD COLUMN commands TEXT;
-- Migrations.java
ALTER TABLE transactions ADD COLUMN free_slots INTEGER NOT NULL DEFAULT 0;
-- Migrations.java
ALTER TABLE deliveries ADD COLUMN commands TEXT;
-- Migrations.java
ALTER TABLE deliveries ADD COLUMN free_slots INTEGER NOT NULL DEFAULT 0;
-- Migrations.java
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
