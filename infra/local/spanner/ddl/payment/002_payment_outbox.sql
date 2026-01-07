CREATE TABLE PaymentOutbox (
  OutboxId STRING(36) NOT NULL,
  AggregateType STRING(64) NOT NULL,
  AggregateId STRING(64) NOT NULL,
  EventType STRING(64) NOT NULL,
  Topic STRING(128) NOT NULL,
  KafkaKey STRING(128) NOT NULL,
  PayloadJson STRING(MAX) NOT NULL,
  Status STRING(16) NOT NULL,
  AttemptCount INT64 NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  LastAttemptAt TIMESTAMP,
  LastError STRING(1000),
  LockedBy STRING(128),
  LeaseUntil TIMESTAMP
) PRIMARY KEY (OutboxId);

CREATE INDEX PaymentOutboxByStatus ON PaymentOutbox(Status, CreatedAt);

-- Index to support reclaiming expired PROCESSING events (Status, LeaseUntil, CreatedAt)
CREATE INDEX PaymentOutboxByStatusLease ON PaymentOutbox(Status, LeaseUntil, CreatedAt);

