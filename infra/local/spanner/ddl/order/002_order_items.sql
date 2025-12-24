CREATE TABLE OrderItems (
  OrderId STRING(36) NOT NULL,
  LineId INT64 NOT NULL,
  SkuId STRING(64) NOT NULL,
  ProductId STRING(64),
  TitleSnapshot STRING(256),
  ImageSnapshot STRING(512),
  Quantity INT64 NOT NULL,
  UnitPriceCents INT64 NOT NULL,
  Currency STRING(8) NOT NULL,
  LineSubtotalCents INT64 NOT NULL,
  LineDiscountCents INT64 NOT NULL,
  LineTaxCents INT64 NOT NULL,
  LineTotalCents INT64 NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (OrderId, LineId),
  INTERLEAVE IN PARENT Orders ON DELETE CASCADE;

