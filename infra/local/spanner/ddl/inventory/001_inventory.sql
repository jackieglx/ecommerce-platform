CREATE TABLE Inventory (
  SkuId STRING(36) NOT NULL,
  Available INT64 NOT NULL,
  Reserved INT64 NOT NULL,
  UpdatedAt TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
) PRIMARY KEY (SkuId);

