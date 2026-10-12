-- Migrations.java
CREATE TABLE islands ( id TEXT PRIMARY KEY, name TEXT NOT NULL, name_lower TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, owner_name TEXT, type TEXT NOT NULL, template TEXT, grid_x INTEGER NOT NULL, grid_z INTEGER NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL, home_x REAL NOT NULL DEFAULT 0, home_y REAL NOT NULL DEFAULT 0, home_z REAL NOT NULL DEFAULT 0, home_yaw REAL NOT NULL DEFAULT 0, home_pitch REAL NOT NULL DEFAULT 0, money TEXT NOT NULL DEFAULT '0', shards TEXT NOT NULL DEFAULT '0', exp INTEGER NOT NULL DEFAULT 0, value INTEGER NOT NULL DEFAULT 0, upgrades TEXT, ob_upgrades TEXT, admin_coop_bonus INTEGER NOT NULL DEFAULT 0, admin_crop_bonus REAL NOT NULL DEFAULT 0, settings TEXT, permissions TEXT, gen_x INTEGER NOT NULL DEFAULT 0, gen_y INTEGER NOT NULL DEFAULT 0, gen_z INTEGER NOT NULL DEFAULT 0, ob_breaks INTEGER NOT NULL DEFAULT 0, regen_pending INTEGER NOT NULL DEFAULT 0, hoppers INTEGER NOT NULL DEFAULT 0, spawners INTEGER NOT NULL DEFAULT 0, safe_until INTEGER NOT NULL DEFAULT 0, raid_cooldown_until INTEGER NOT NULL DEFAULT 0, raid_id TEXT, raid_attacker TEXT, raid_ends INTEGER NOT NULL DEFAULT 0 );
-- Migrations.java
CREATE UNIQUE INDEX islands_grid ON islands(grid_x, grid_z);
-- Migrations.java
CREATE INDEX islands_owner ON islands(owner);
-- Migrations.java
CREATE INDEX islands_value ON islands(value DESC, created_at ASC);
-- Migrations.java
CREATE TABLE island_members ( island_id TEXT NOT NULL, uuid TEXT NOT NULL, rank TEXT NOT NULL, PRIMARY KEY (island_id, uuid) );
-- Migrations.java
CREATE INDEX members_uuid ON island_members(uuid);
-- Migrations.java
CREATE TABLE island_coops ( island_id TEXT NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY (island_id, uuid) );
-- Migrations.java
CREATE INDEX coops_uuid ON island_coops(uuid);
-- Migrations.java
CREATE TABLE island_chests ( island_id TEXT PRIMARY KEY, version INTEGER NOT NULL, data BLOB );
-- Migrations.java
CREATE TABLE journal ( op_id TEXT PRIMARY KEY, island_id TEXT, player TEXT, type TEXT NOT NULL, payload TEXT, state TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL );
-- Migrations.java
CREATE INDEX journal_state ON journal(state);
-- Migrations.java
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
-- Migrations.java
CREATE TABLE players ( uuid TEXT PRIMARY KEY, name TEXT NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0 );
-- Migrations.java
CREATE INDEX players_name ON players(name COLLATE NOCASE);
-- Migrations.java
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
