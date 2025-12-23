CREATE TABLE InventoryReservations (
  OrderId STRING(36) NOT NULL,
  SkuId STRING(36) NOT NULL,
  Qty INT64 NOT NULL,
  Status STRING(32) NOT NULL,
  ExpireAt TIMESTAMP NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (OrderId, SkuId);

CREATE INDEX ReservationsByExpire ON InventoryReservations(ExpireAt);

