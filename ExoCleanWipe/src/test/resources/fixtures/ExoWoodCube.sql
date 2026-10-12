-- Database.java
CREATE TABLE IF NOT EXISTS ledger ( id INTEGER PRIMARY KEY AUTOINCREMENT, cube_id TEXT, actor TEXT, payee TEXT, kind TEXT NOT NULL CHECK (kind IN ('SALE', 'UPGRADE', 'AUTOSELL')), upgrade TEXT, from_level INTEGER NOT NULL DEFAULT 0, to_level INTEGER NOT NULL DEFAULT 0, amount INTEGER NOT NULL CHECK (amount >= 0), items TEXT, status TEXT NOT NULL, note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
-- Database.java
CREATE TABLE IF NOT EXISTS cubes ( id TEXT PRIMARY KEY, owner TEXT NOT NULL, tier TEXT NOT NULL, size INTEGER NOT NULL CHECK (size BETWEEN 3 AND 15), state TEXT NOT NULL CHECK (state IN ('ITEM', 'PLACED', 'REMOVED')), token TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, facing TEXT, speed_level INTEGER NOT NULL DEFAULT 1 CHECK (speed_level >= 1), storage_level INTEGER NOT NULL DEFAULT 1 CHECK (storage_level >= 1), multiplier_level INTEGER NOT NULL DEFAULT 1 CHECK (multiplier_level >= 1), sell_unlocked INTEGER NOT NULL DEFAULT 0 CHECK (sell_unlocked IN (0, 1)), autosell_unlocked INTEGER NOT NULL DEFAULT 0 CHECK (autosell_unlocked IN (0, 1)), autosell_enabled INTEGER NOT NULL DEFAULT 0 CHECK (autosell_enabled IN (0, 1)), autosell_pending INTEGER NOT NULL DEFAULT 0 CHECK (autosell_pending >= 0), automine_unlocked INTEGER NOT NULL DEFAULT 0 CHECK (automine_unlocked IN (0, 1)), automine_enabled INTEGER NOT NULL DEFAULT 0 CHECK (automine_enabled IN (0, 1)), legacy_head INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0, issued_by TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, CHECK (state <> 'ITEM' OR token IS NOT NULL), CHECK (state <> 'PLACED' OR (world IS NOT NULL AND facing IS NOT NULL AND token IS NULL)));
-- Database.java
CREATE TABLE IF NOT EXISTS cube_items ( cube_id TEXT NOT NULL REFERENCES cubes(id) ON DELETE CASCADE, material TEXT NOT NULL, amount INTEGER NOT NULL CHECK (amount > 0), PRIMARY KEY (cube_id, material));
-- Database.java
CREATE TABLE IF NOT EXISTS audit ( id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT, cube_id TEXT, action TEXT NOT NULL, detail TEXT);
-- Database.java
CREATE INDEX IF NOT EXISTS cubes_owner ON cubes(owner);
-- Database.java
CREATE UNIQUE INDEX IF NOT EXISTS cubes_token ON cubes(token) WHERE token IS NOT NULL;
-- Database.java
CREATE INDEX IF NOT EXISTS ledger_status ON ledger(status);
-- Database.java
CREATE INDEX IF NOT EXISTS audit_cube ON audit(cube_id);
