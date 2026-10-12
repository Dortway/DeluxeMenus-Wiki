-- Storage.java
CREATE TABLE IF NOT EXISTS progress(uuid TEXT,skill TEXT,level INTEGER,xp REAL,PRIMARY KEY(uuid,skill));
-- Storage.java
CREATE TABLE IF NOT EXISTS names(uuid TEXT PRIMARY KEY,name TEXT);
-- Storage.java
CREATE TABLE IF NOT EXISTS placed(location TEXT PRIMARY KEY);
