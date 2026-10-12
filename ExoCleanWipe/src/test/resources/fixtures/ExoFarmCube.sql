-- Database.java
CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
-- Database.java
CREATE TABLE cubes ( id TEXT PRIMARY KEY, owner TEXT NOT NULL, tier TEXT NOT NULL CHECK (tier IN ('STARTER','PREMIUM')), state TEXT NOT NULL CHECK (state IN ('ITEM','PLACING','PLACED','REMOVED')), world TEXT, x INTEGER, y INTEGER, z INTEGER, active INTEGER NOT NULL DEFAULT 0, kelp INTEGER NOT NULL DEFAULT 0 CHECK (kelp >= 0), dried_kelp INTEGER NOT NULL DEFAULT 0 CHECK (dried_kelp >= 0), dried_kelp_block INTEGER NOT NULL DEFAULT 0 CHECK (dried_kelp_block >= 0), levels TEXT NOT NULL DEFAULT '', disabled TEXT NOT NULL DEFAULT '', total_generated INTEGER NOT NULL DEFAULT 0, total_earned TEXT NOT NULL DEFAULT '0.00', version INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, CHECK ((state IN ('PLACING','PLACED')) = (world IS NOT NULL)));
-- Database.java
CREATE UNIQUE INDEX cubes_anchor ON cubes(world, x, y, z) WHERE state IN ('PLACING','PLACED');
-- Database.java
CREATE INDEX cubes_owner ON cubes(owner);
-- Database.java
CREATE TABLE tokens ( id TEXT PRIMARY KEY, cube_id TEXT NOT NULL REFERENCES cubes(id), state TEXT NOT NULL CHECK (state IN ('ISSUED','CONSUMED','REVOKED')), issued_to TEXT, reason TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
CREATE UNIQUE INDEX tokens_one_live ON tokens(cube_id) WHERE state = 'ISSUED';
-- Database.java
CREATE TABLE ledger ( id INTEGER PRIMARY KEY AUTOINCREMENT, cube_id TEXT NOT NULL, player TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, amount TEXT NOT NULL, items TEXT NOT NULL DEFAULT '', upgrade TEXT, from_level INTEGER NOT NULL DEFAULT 0, to_level INTEGER NOT NULL DEFAULT 0, note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
CREATE INDEX ledger_status ON ledger(status);
-- Database.java
CREATE INDEX ledger_cube ON ledger(cube_id);
-- Database.java
CREATE TABLE audit ( id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT, cube_id TEXT, action TEXT NOT NULL, detail TEXT);
-- Database.java
CREATE INDEX audit_cube ON audit(cube_id);
-- Database.java
ALTER TABLE cubes ADD COLUMN fuel INTEGER NOT NULL DEFAULT 0 CHECK (fuel >= 0);
