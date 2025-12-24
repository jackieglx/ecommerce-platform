package com.lingxiao.order.infrastructure.db.spanner;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.Value;
import com.lingxiao.common.db.tx.TxRunner;
import com.lingxiao.contracts.events.FlashSaleReservedEventV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

    private final DatabaseClient databaseClient;
    private final TxRunner txRunner;

    public OrderRepository(DatabaseClient databaseClient, TxRunner txRunner) {
        this.databaseClient = databaseClient;
        this.txRunner = txRunner;
    }

    public void createFromFlashSaleEvent(FlashSaleReservedEventV2 event) {
        txRunner.runReadWrite(tx -> {
            String idempotencyKey = "fs:v2:" + event.eventId();
            Key key = Key.of(event.userId(), idempotencyKey);
            Struct existing = tx.readRow("OrderIdempotency", key, List.of("OrderId"));
            if (existing != null) {
                log.debug("Duplicate flash sale event ignored eventId={} orderId={} userId={}", event.eventId(), event.orderId(), event.userId());
                return null;
            }

            Instant expireAt = event.expireAt() != null ? event.expireAt() : event.occurredAt().plusSeconds(300);
            long subtotal = event.priceCents() * event.qty();
            long discount = 0;
            long tax = 0;
            long shipping = 0;
            long total = subtotal - discount + tax + shipping;

            Mutation orderMutation = Mutation.newInsertBuilder("Orders")
                    .set("OrderId").to(event.orderId())
                    .set("UserId").to(event.userId())
                    .set("Status").to("PENDING_PAYMENT")
                    .set("StatusVersion").to(1L)
                    .set("ExpireAt").to(Timestamp.ofTimeSecondsAndNanos(expireAt.getEpochSecond(), expireAt.getNano()))
                    .set("Currency").to(event.currency())
                    .set("SubtotalCents").to(subtotal)
                    .set("DiscountCents").to(discount)
                    .set("TaxCents").to(tax)
                    .set("ShippingCents").to(shipping)
                    .set("TotalCents").to(total)
                    .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            Mutation itemMutation = Mutation.newInsertBuilder("OrderItems")
                    .set("OrderId").to(event.orderId())
                    .set("LineId").to(1L)
                    .set("SkuId").to(event.skuId())
                    .set("Quantity").to(event.qty())
                    .set("UnitPriceCents").to(event.priceCents())
                    .set("Currency").to(event.currency())
                    .set("LineSubtotalCents").to(subtotal)
                    .set("LineDiscountCents").to(discount)
                    .set("LineTaxCents").to(tax)
                    .set("LineTotalCents").to(total)
                    .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                    .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            Mutation idemMutation = Mutation.newInsertBuilder("OrderIdempotency")
                    .set("UserId").to(event.userId())
                    .set("IdempotencyKey").to(idempotencyKey)
                    .set("OrderId").to(event.orderId())
                    .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                    .build();

            List<Mutation> mutations = new ArrayList<>();
            mutations.add(orderMutation);
            mutations.add(itemMutation);
            mutations.add(idemMutation);
            tx.buffer(mutations);
            return null;
        });
    }
}

