CREATE TABLE Orders (
  OrderId STRING(64) NOT NULL,
  UserId STRING(36) NOT NULL,
  Status STRING(32) NOT NULL,
  StatusVersion INT64 NOT NULL,
  ExpireAt TIMESTAMP NOT NULL,
  Currency STRING(8) NOT NULL,
  SubtotalCents INT64 NOT NULL,
  DiscountCents INT64 NOT NULL,
  TaxCents INT64 NOT NULL,
  ShippingCents INT64 NOT NULL,
  TotalCents INT64 NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (OrderId);

CREATE INDEX OrdersByUser ON Orders(UserId, CreatedAt DESC, OrderId);
CREATE INDEX OrdersByExpire ON Orders(Status, ExpireAt, OrderId);
