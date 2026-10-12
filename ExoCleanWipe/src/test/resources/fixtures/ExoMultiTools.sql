-- Migrations.java
CREATE TABLE players ( uuid TEXT PRIMARY KEY, name TEXT NOT NULL, shards INTEGER NOT NULL DEFAULT 0 CHECK (shards >= 0), primary_tool TEXT, version INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX players_name ON players(name COLLATE NOCASE);
-- Migrations.java
CREATE TABLE player_prefs (uuid TEXT NOT NULL, pref TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (uuid, pref));
-- Migrations.java
CREATE TABLE daily_counters (uuid TEXT NOT NULL, counter TEXT NOT NULL, day INTEGER NOT NULL, value REAL NOT NULL, PRIMARY KEY (uuid, counter));
-- Migrations.java
CREATE TABLE sell_ledger (uuid TEXT NOT NULL, entry TEXT NOT NULL, amount INTEGER NOT NULL CHECK (amount >= 0), PRIMARY KEY (uuid, entry));
-- Migrations.java
CREATE TABLE tools ( id TEXT PRIMARY KEY, owner TEXT NOT NULL, owner_name TEXT, active_mode TEXT NOT NULL, nonce INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX tools_owner ON tools(owner);
-- Migrations.java
CREATE TABLE tool_modes (tool_id TEXT NOT NULL, mode TEXT NOT NULL, level INTEGER NOT NULL, xp INTEGER NOT NULL, PRIMARY KEY (tool_id, mode));
-- Migrations.java
CREATE TABLE tool_enchants (tool_id TEXT NOT NULL, mode TEXT NOT NULL, enchant TEXT NOT NULL, enchant_rank INTEGER NOT NULL, enabled INTEGER NOT NULL, PRIMARY KEY (tool_id, mode, enchant));
-- Migrations.java
CREATE TABLE tool_stats (tool_id TEXT NOT NULL, mode TEXT NOT NULL, stat TEXT NOT NULL, value REAL NOT NULL, PRIMARY KEY (tool_id, mode, stat));
-- Migrations.java
CREATE INDEX tool_stats_stat ON tool_stats(stat, value);
-- Migrations.java
CREATE TABLE milestone_claims (tool_id TEXT NOT NULL, mode TEXT NOT NULL, level INTEGER NOT NULL, status TEXT NOT NULL, claimed_at INTEGER NOT NULL, PRIMARY KEY (tool_id, mode, level));
-- Migrations.java
CREATE TABLE transactions ( id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, player TEXT NOT NULL, tool_id TEXT, mode TEXT, kind TEXT NOT NULL, shards INTEGER NOT NULL DEFAULT 0, money REAL NOT NULL DEFAULT 0, details TEXT, status TEXT NOT NULL, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX transactions_player ON transactions(player, created_at);
-- Migrations.java
CREATE INDEX transactions_status ON transactions(status);
-- Migrations.java
CREATE TABLE audit ( id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, actor TEXT NOT NULL, target TEXT, tool_id TEXT, action TEXT NOT NULL, details TEXT );
-- Migrations.java
CREATE INDEX audit_tool ON audit(tool_id, created_at);
