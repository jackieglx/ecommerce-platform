CREATE TABLE Payments (
  PaymentId STRING(36) NOT NULL,
  OrderId STRING(64) NOT NULL,
  Status STRING(32) NOT NULL,
  AmountCents INT64 NOT NULL,
  Currency STRING(8) NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (PaymentId);

