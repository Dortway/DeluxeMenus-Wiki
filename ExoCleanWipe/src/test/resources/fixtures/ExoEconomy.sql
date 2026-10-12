-- Database.java
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- Database.java
CREATE TABLE transactions( id TEXT PRIMARY KEY, player TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, parent TEXT, details TEXT NOT NULL, balance_before TEXT, balance_after TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL, note TEXT);
-- Database.java
CREATE INDEX tx_player ON transactions(player, created);
-- Database.java
CREATE INDEX tx_state ON transactions(state);
-- Database.java
CREATE UNIQUE INDEX tx_parent_kind ON transactions(parent, kind) WHERE parent IS NOT NULL AND state NOT IN ('FAILED','ABORTED','RESOLVED_FAILED');
-- Database.java
CREATE TABLE boosters( id TEXT PRIMARY KEY, tier TEXT NOT NULL, scope TEXT NOT NULL, owner TEXT, activity TEXT NOT NULL, multiplier TEXT NOT NULL, state TEXT NOT NULL, expires_at INTEGER NOT NULL, remaining_ms INTEGER NOT NULL, sequence INTEGER NOT NULL, paused_by_server INTEGER NOT NULL, source_tx TEXT, created INTEGER NOT NULL);
-- Database.java
CREATE INDEX boosters_state ON boosters(state);
-- Database.java
CREATE TABLE escrow( session TEXT PRIMARY KEY, player TEXT NOT NULL, state TEXT NOT NULL, tx TEXT, items BLOB NOT NULL, summary TEXT NOT NULL, updated INTEGER NOT NULL);
-- Database.java
CREATE TABLE claims( id TEXT PRIMARY KEY, player TEXT NOT NULL, state TEXT NOT NULL, tx TEXT, reason TEXT NOT NULL, items BLOB NOT NULL, summary TEXT NOT NULL, created INTEGER NOT NULL);
-- Database.java
CREATE INDEX claims_player ON claims(player, state);
-- Database.java
CREATE TABLE placed_blocks( world TEXT NOT NULL, chunk INTEGER NOT NULL, pos INTEGER NOT NULL, kind INTEGER NOT NULL, PRIMARY KEY(world, chunk, pos)) WITHOUT ROWID;
-- Database.java
CREATE TABLE activity_daily( player TEXT NOT NULL, day INTEGER NOT NULL, activity TEXT NOT NULL, amount TEXT NOT NULL, PRIMARY KEY(player, day, activity)) WITHOUT ROWID;
-- Database.java
CREATE TABLE player_prefs(player TEXT PRIMARY KEY, muted INTEGER NOT NULL);
-- Database.java
ALTER TABLE placed_blocks ADD COLUMN type TEXT;
