-- Migrations.java
CREATE TABLE IF NOT EXISTS transactions ( id TEXT PRIMARY KEY, kind TEXT NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, product_id TEXT NOT NULL DEFAULT '', tokens INTEGER NOT NULL, money_base TEXT, money_fee TEXT, money_total TEXT, commands TEXT NOT NULL DEFAULT '', progress INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_tx_state ON transactions(state);
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_tx_player ON transactions(player_uuid, state);
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_tx_created ON transactions(created_at);
-- Migrations.java
CREATE TABLE IF NOT EXISTS tx_events ( seq INTEGER PRIMARY KEY AUTOINCREMENT, tx_id TEXT NOT NULL, at INTEGER NOT NULL, from_state TEXT, to_state TEXT NOT NULL, actor TEXT NOT NULL, note TEXT NOT NULL DEFAULT '' );
-- Migrations.java
CREATE INDEX IF NOT EXISTS idx_events_tx ON tx_events(tx_id);
-- Migrations.java
ALTER TABLE transactions ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1;
