-- SqliteRedemptionStore.java
CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL);
-- SqliteRedemptionStore.java
CREATE TABLE IF NOT EXISTS redemptions(pouch_uuid TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, reward_type TEXT NOT NULL, amount INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('CLAIMED','COMPLETED')), claimed_at INTEGER NOT NULL, completed_at INTEGER);
