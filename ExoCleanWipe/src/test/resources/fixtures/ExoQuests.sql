-- Migrations.java
CREATE TABLE players ( uuid TEXT PRIMARY KEY, points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0), updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE TABLE point_ledger ( id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, delta INTEGER NOT NULL, balance_after INTEGER NOT NULL CHECK (balance_after >= 0), reason TEXT NOT NULL, ref_id TEXT NOT NULL UNIQUE, actor TEXT NOT NULL, created_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX idx_ledger_uuid ON point_ledger(uuid, created_at);
-- Migrations.java
CREATE TABLE daily_assignments ( uuid TEXT NOT NULL, period TEXT NOT NULL, slot INTEGER NOT NULL CHECK (slot BETWEEN 0 AND 2), quest_id TEXT NOT NULL, target INTEGER NOT NULL CHECK (target > 0), reward INTEGER NOT NULL CHECK (reward > 0), progress INTEGER NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= target), assigned_at INTEGER NOT NULL, completed_at INTEGER, completion_id TEXT UNIQUE, PRIMARY KEY (uuid, period, slot), UNIQUE (uuid, period, quest_id) );
-- Migrations.java
CREATE INDEX idx_assignments_period ON daily_assignments(period);
-- Migrations.java
CREATE TABLE quest_completions ( completion_id TEXT PRIMARY KEY, uuid TEXT NOT NULL, period TEXT NOT NULL, slot INTEGER NOT NULL, quest_id TEXT NOT NULL, reward INTEGER NOT NULL, credited INTEGER NOT NULL, completed_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX idx_completions_uuid ON quest_completions(uuid, period);
-- Migrations.java
CREATE TABLE purchases ( purchase_id TEXT PRIMARY KEY, uuid TEXT NOT NULL, item_id TEXT NOT NULL, price INTEGER NOT NULL CHECK (price > 0), revision TEXT NOT NULL, reward_type TEXT NOT NULL, reward_snapshot TEXT NOT NULL, state TEXT NOT NULL CHECK (state IN ('PENDING','DELIVERING','DELIVERED','REFUNDED','NEEDS_REVIEW','RESOLVED')), note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX idx_purchases_state ON purchases(state);
-- Migrations.java
CREATE INDEX idx_purchases_uuid ON purchases(uuid, state);
-- Migrations.java
CREATE TABLE placed_blocks ( world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, material TEXT NOT NULL, kind INTEGER NOT NULL, owner TEXT, placed_at INTEGER NOT NULL, PRIMARY KEY (world, x, y, z) ) WITHOUT ROWID;
-- Migrations.java
CREATE TABLE audit_log ( id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT, details TEXT, created_at INTEGER NOT NULL );
-- Migrations.java
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
