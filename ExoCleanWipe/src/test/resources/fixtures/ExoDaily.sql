-- SqliteStore.java
CREATE TABLE players ( uuid TEXT PRIMARY KEY NOT NULL, last_name TEXT, first_use_date TEXT NOT NULL, cycle_number INTEGER NOT NULL CHECK (cycle_number >= 1), cycle_start_date TEXT NOT NULL, cycle_length INTEGER NOT NULL CHECK (cycle_length >= 1), updated_at INTEGER NOT NULL );
-- SqliteStore.java
CREATE INDEX players_name ON players (last_name COLLATE NOCASE);
-- SqliteStore.java
CREATE TABLE assignments ( uuid TEXT NOT NULL, cycle INTEGER NOT NULL, day INTEGER NOT NULL, position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 3), reward_date TEXT NOT NULL, pool_id TEXT NOT NULL, reward_id TEXT NOT NULL, snapshot TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY (uuid, cycle, day, position) );
-- SqliteStore.java
CREATE TABLE claims ( claim_id INTEGER PRIMARY KEY AUTOINCREMENT, claim_key TEXT NOT NULL UNIQUE, uuid TEXT NOT NULL, cycle INTEGER NOT NULL, day INTEGER NOT NULL, position INTEGER NOT NULL CHECK (position BETWEEN 1 AND 3), reward_date TEXT NOT NULL, reward_id TEXT NOT NULL, state TEXT NOT NULL CHECK (state IN ('RESERVED', 'DELIVERING', 'DELIVERED', 'UNCERTAIN')), attempt_id TEXT NOT NULL, reserved_at INTEGER NOT NULL, delivering_at INTEGER, delivered_at INTEGER, note TEXT, UNIQUE (uuid, cycle, day, position) );
-- SqliteStore.java
CREATE INDEX claims_state ON claims (state);
-- SqliteStore.java
CREATE INDEX claims_date ON claims (uuid, reward_date);
-- SqliteStore.java
CREATE TABLE audit_log ( id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, details TEXT NOT NULL );
-- SqliteStore.java
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
