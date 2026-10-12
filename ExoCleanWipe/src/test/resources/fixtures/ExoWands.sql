-- Storage.java
CREATE TABLE IF NOT EXISTS tokens (player TEXT PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0);
-- Storage.java
CREATE TABLE IF NOT EXISTS pending_delivery (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, item BLOB NOT NULL, kind TEXT NOT NULL, created INTEGER NOT NULL);
-- Storage.java
CREATE INDEX IF NOT EXISTS idx_pending_player ON pending_delivery(player);
-- Storage.java
CREATE TABLE IF NOT EXISTS retired_wands (id TEXT PRIMARY KEY, retired_at INTEGER NOT NULL, reason TEXT);
-- Storage.java
CREATE TABLE IF NOT EXISTS journal (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, player TEXT NOT NULL, action TEXT NOT NULL, wand_type TEXT, wand_id TEXT, currency TEXT, amount TEXT, details TEXT);
-- Storage.java
CREATE TABLE IF NOT EXISTS build_jobs (id TEXT PRIMARY KEY, player TEXT NOT NULL, world TEXT NOT NULL, material TEXT NOT NULL, positions BLOB NOT NULL, charged TEXT NOT NULL, free INTEGER NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL);
