CREATE TABLE PendingPayments (
  OrderId STRING(64) NOT NULL,
  PaymentId STRING(36) NOT NULL,
  AmountCents INT64 NOT NULL,
  Currency STRING(8) NOT NULL,
  PaidAt TIMESTAMP NOT NULL,
  Status STRING(16) NOT NULL, -- PENDING / APPLIED / REFUND_REQUIRED
  RawEventJson STRING(MAX),
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (OrderId);

CREATE INDEX PendingPaymentsByStatus ON PendingPayments(Status, PaidAt, OrderId);

