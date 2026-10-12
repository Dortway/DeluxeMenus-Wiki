-- Database.java
CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
-- Database.java
CREATE TABLE tools ( id TEXT PRIMARY KEY, owner TEXT NOT NULL, material TEXT NOT NULL, level INTEGER NOT NULL CHECK (level >= 1 AND level <= 255), charge INTEGER NOT NULL CHECK (charge >= 0), charge_seq INTEGER NOT NULL DEFAULT 0, nonce TEXT NOT NULL, prev_nonce TEXT, nonce_session TEXT, state TEXT NOT NULL CHECK (state IN ('ACTIVE','LOCKED','REVOKED','DESTROYED')), note TEXT, version INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
CREATE INDEX tools_owner ON tools(owner);
-- Database.java
CREATE TABLE ledger ( id TEXT PRIMARY KEY, tool_id TEXT NOT NULL REFERENCES tools(id), player TEXT NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('UPGRADE','RECHARGE','REPAIR')), status TEXT NOT NULL, amount TEXT NOT NULL, from_level INTEGER NOT NULL DEFAULT 0, to_level INTEGER NOT NULL DEFAULT 0, charge_amount INTEGER NOT NULL DEFAULT 0, note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
CREATE INDEX ledger_status ON ledger(status);
-- Database.java
CREATE INDEX ledger_tool ON ledger(tool_id);
-- Database.java
CREATE TABLE audit ( id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, actor TEXT, tool_id TEXT, action TEXT NOT NULL, detail TEXT);
-- Database.java
CREATE INDEX audit_tool ON audit(tool_id);
-- Database.java
ALTER TABLE tools ADD COLUMN type TEXT NOT NULL DEFAULT 'pickaxe';
-- Database.java
CREATE TABLE ledger_v3 ( id TEXT PRIMARY KEY, tool_id TEXT NOT NULL REFERENCES tools(id), player TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, amount TEXT NOT NULL, from_level INTEGER NOT NULL DEFAULT 0, to_level INTEGER NOT NULL DEFAULT 0, charge_amount INTEGER NOT NULL DEFAULT 0, note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
-- Database.java
DROP TABLE ledger;
ALTER TABLE ledger_v3 RENAME TO ledger;
