CREATE TABLE Skus (
  SkuId STRING(36) NOT NULL,
  ProductId STRING(36) NOT NULL,
  Title STRING(MAX),
  Status STRING(32) NOT NULL,
  Brand STRING(128),
  PriceCents INT64 NOT NULL,
  Currency STRING(8) NOT NULL,
  CreatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (SkuId);

