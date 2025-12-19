CREATE TABLE Orders (
  OrderId STRING(36) NOT NULL,
  UserId STRING(36) NOT NULL,
  Status STRING(32) NOT NULL,
  TotalCents INT64 NOT NULL,
  Currency STRING(8) NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (OrderId);

