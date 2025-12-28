package com.lingxiao.catalog.infrastructure.db.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Value;
import com.lingxiao.catalog.api.dto.CreateSkuRequest;
import com.lingxiao.catalog.api.dto.UpdateSkuRequest;
import com.lingxiao.catalog.domain.model.Sku;
import com.lingxiao.catalog.infrastructure.db.SkuRepository;
import com.lingxiao.common.db.errors.NotFoundException;
import com.lingxiao.common.db.errors.SpannerErrorTranslator;
import com.lingxiao.common.db.repo.BaseRepositorySupport;
import com.lingxiao.common.db.tx.TxRunner;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class SpannerSkuRepository extends BaseRepositorySupport implements SkuRepository {

    public SpannerSkuRepository(DatabaseClient databaseClient,
                                SpannerErrorTranslator translator,
                                TxRunner txRunner) {
        super(databaseClient, translator, txRunner);
    }

    @Override
    public Sku create(CreateSkuRequest request) {
        inReadWrite(tx -> {
            Mutation mutation = Mutation.newInsertBuilder("Skus")
                .set("SkuId").to(request.skuId())
                .set("ProductId").to(request.productId())
                .set("Title").to(request.title())
                .set("Status").to(request.status())
                .set("Brand").to(request.brand())
                .set("PriceCents").to(request.priceCents())
                .set("Currency").to(request.currency())
                .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                .build();
            tx.buffer(mutation);
            return null;
        });
        return get(request.skuId());
    }

    @Override
    public Sku get(String skuId) {
        try {
            return inReadOnly(tx -> loadById(tx, skuId)
                    .orElseThrow(() -> new NotFoundException("sku not found: " + skuId)));
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    @Override
    public List<Sku> batchGet(List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        try {
            return inReadOnly(tx -> {
                Statement stmt = Statement.newBuilder(
                                "SELECT SkuId, ProductId, Title, Status, Brand, PriceCents, Currency, CreatedAt, UpdatedAt " +
                                        "FROM Skus WHERE SkuId IN UNNEST(@ids)")
                        .bind("ids").toStringArray(skuIds)
                        .build();
                try (ResultSet rs = tx.executeQuery(stmt)) {
                    List<Sku> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            });
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    @Override
    public Sku update(String skuId, UpdateSkuRequest request) {
        try {
            inReadWrite(tx -> {
                Mutation.WriteBuilder builder = Mutation.newUpdateBuilder("Skus")
                        .set("SkuId").to(skuId)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP);
                if (request.title() != null) {
                    builder.set("Title").to(request.title());
                }
                if (request.status() != null) {
                    builder.set("Status").to(request.status());
                }
                if (request.brand() != null) {
                    builder.set("Brand").to(request.brand());
                }
                if (request.priceCents() != null) {
                    builder.set("PriceCents").to(request.priceCents());
                }
                if (request.currency() != null) {
                    builder.set("Currency").to(request.currency());
                }
                tx.buffer(builder.build());
                return null;
            });
            return get(skuId);
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    private Optional<Sku> loadById(ReadContext tx, String skuId) {
        Statement stmt = Statement.newBuilder(
                        "SELECT SkuId, ProductId, Title, Status, Brand, PriceCents, Currency, CreatedAt, UpdatedAt " +
                                "FROM Skus WHERE SkuId = @id")
                .bind("id").to(skuId)
                .build();
        try (ResultSet rs = tx.executeQuery(stmt)) {
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        }
    }

    private Sku mapRow(ResultSet rs) {
        return new Sku(
                rs.getString("SkuId"),
                rs.getString("ProductId"),
                rs.getString("Title"),
                rs.getString("Status"),
                rs.isNull("Brand") ? "" : rs.getString("Brand"),
                rs.getLong("PriceCents"),
                rs.getString("Currency"),
                Instant.ofEpochSecond(rs.getTimestamp("CreatedAt").getSeconds(), rs.getTimestamp("CreatedAt").getNanos()),
                Instant.ofEpochSecond(rs.getTimestamp("UpdatedAt").getSeconds(), rs.getTimestamp("UpdatedAt").getNanos())
        );
    }
}

