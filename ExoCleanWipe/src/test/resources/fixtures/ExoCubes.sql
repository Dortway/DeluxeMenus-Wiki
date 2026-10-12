-- Database.java
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
-- Database.java
CREATE TABLE IF NOT EXISTS cubes ( id TEXT PRIMARY KEY, owner TEXT NOT NULL, type TEXT NOT NULL, state TEXT NOT NULL, token TEXT NOT NULL, world TEXT, x INTEGER, y INTEGER, z INTEGER, facing TEXT, framed INTEGER NOT NULL DEFAULT 1, size INTEGER NOT NULL, speed_level INTEGER NOT NULL DEFAULT 1, sell_unlocked INTEGER NOT NULL DEFAULT 0, autosell_unlocked INTEGER NOT NULL DEFAULT 0, autosell_enabled INTEGER NOT NULL DEFAULT 0, autosell_pending REAL NOT NULL DEFAULT 0 CHECK (autosell_pending >= 0), multiplier_level INTEGER NOT NULL DEFAULT 1, storage_level INTEGER NOT NULL DEFAULT 1, automine_unlocked INTEGER NOT NULL DEFAULT 0, automine_enabled INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
CREATE INDEX IF NOT EXISTS cubes_owner ON cubes(owner);
-- Database.java
CREATE TABLE IF NOT EXISTS cube_items ( cube_id TEXT NOT NULL REFERENCES cubes(id) ON DELETE CASCADE, material TEXT NOT NULL, amount INTEGER NOT NULL CHECK (amount > 0), PRIMARY KEY (cube_id, material));
-- Database.java
CREATE TABLE IF NOT EXISTS core_batches ( id TEXT PRIMARY KEY, type TEXT NOT NULL, issued INTEGER NOT NULL CHECK (issued > 0), used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0 AND used <= issued), issued_to TEXT, issued_by TEXT, created_at INTEGER NOT NULL);
-- Database.java
CREATE TABLE IF NOT EXISTS ledger ( id INTEGER PRIMARY KEY AUTOINCREMENT, cube_id TEXT, actor TEXT, kind TEXT NOT NULL, amount REAL NOT NULL, detail TEXT, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
CREATE INDEX IF NOT EXISTS ledger_status ON ledger(status);
