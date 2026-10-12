-- Database.java
CREATE TABLE collectors(id TEXT PRIMARY KEY, snapshot BLOB NOT NULL);
-- Database.java
CREATE TABLE transactions(id TEXT PRIMARY KEY,collector TEXT NOT NULL,kind TEXT NOT NULL,phase TEXT NOT NULL,detail TEXT NOT NULL,before_snapshot BLOB NOT NULL,after_snapshot BLOB NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
-- Database.java
CREATE UNIQUE INDEX one_pending ON transactions(collector) WHERE phase NOT IN ('DONE','ABORTED','RESOLVED_BEFORE','RESOLVED_AFTER');
