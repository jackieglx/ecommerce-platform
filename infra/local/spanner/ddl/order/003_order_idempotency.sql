CREATE TABLE OrderIdempotency (
  UserId STRING(64) NOT NULL,
  IdempotencyKey STRING(128) NOT NULL,
  OrderId STRING(64) NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (UserId, IdempotencyKey);

