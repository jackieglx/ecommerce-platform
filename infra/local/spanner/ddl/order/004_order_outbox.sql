CREATE TABLE OrderOutbox (
  OutboxId STRING(64) NOT NULL,
  EventType STRING(64) NOT NULL,
  AggregateId STRING(36) NOT NULL,
  PayloadJson STRING(MAX) NOT NULL,
  Status STRING(16) NOT NULL,
  Attempts INT64 NOT NULL,
  NextAttemptAt TIMESTAMP NOT NULL,
  LockedBy STRING(128),
  LockedAt TIMESTAMP,
  LastError STRING(MAX),
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (OutboxId);

CREATE INDEX OrderOutboxByStatus ON OrderOutbox(Status, NextAttemptAt, CreatedAt, OutboxId);

