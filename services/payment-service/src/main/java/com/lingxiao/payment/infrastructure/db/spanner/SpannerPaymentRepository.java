package com.lingxiao.payment.infrastructure.db.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;

import java.util.List;
import com.lingxiao.common.db.errors.SpannerErrorTranslator;
import com.lingxiao.common.db.repo.BaseRepositorySupport;
import com.lingxiao.common.db.tx.TxRunner;
import com.lingxiao.payment.domain.Payment;
import com.lingxiao.payment.infrastructure.db.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class SpannerPaymentRepository extends BaseRepositorySupport implements PaymentRepository {

    private final SpannerOutboxRepository outboxRepository;

    public SpannerPaymentRepository(DatabaseClient databaseClient,
                                   SpannerErrorTranslator translator,
                                   TxRunner txRunner,
                                   SpannerOutboxRepository outboxRepository) {
        super(databaseClient, translator, txRunner);
        this.outboxRepository = outboxRepository;
    }

    @Override
    public Payment create(String paymentId, String orderId, String status, long amountCents, String currency) {
        try {
            inReadWrite(tx -> {
                Mutation mutation = Mutation.newInsertBuilder("Payments")
                        .set("PaymentId").to(paymentId)
                        .set("OrderId").to(orderId)
                        .set("Status").to(status)
                        .set("AmountCents").to(amountCents)
                        .set("Currency").to(currency)
                        .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                tx.buffer(mutation);
                return null;
            });
            // 插入成功：按 paymentId 查回来
            return getByPaymentId(paymentId).orElseThrow();
        } catch (com.google.cloud.spanner.SpannerException e) {
            if (e.getErrorCode() == com.google.cloud.spanner.ErrorCode.ALREADY_EXISTS) {
                // 命中 UNIQUE INDEX PaymentsByOrderId：返回已存在的 payment
                return getByOrderId(orderId).orElseThrow();
            }
            throw translator.translate(e);
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    /**
     * Create payment and insert outbox event in the same transaction.
     * @param paymentId payment ID
     * @param orderId order ID
     * @param status payment status
     * @param amountCents amount in cents
     * @param currency currency
     * @param outboxId outbox event ID
     * @param eventType event type
     * @param topic Kafka topic
     * @param key Kafka key
     * @param payloadJson event payload JSON
     * @return created payment
     */
    public Payment createWithOutbox(String paymentId, String orderId, String status, long amountCents, String currency,
                                    String outboxId, String eventType, String topic, String key, String payloadJson) {
        try {
            inReadWrite(tx -> {
                Mutation mutation = Mutation.newInsertBuilder("Payments")
                        .set("PaymentId").to(paymentId)
                        .set("OrderId").to(orderId)
                        .set("Status").to(status)
                        .set("AmountCents").to(amountCents)
                        .set("Currency").to(currency)
                        .set("CreatedAt").to(Value.COMMIT_TIMESTAMP)
                        .set("UpdatedAt").to(Value.COMMIT_TIMESTAMP)
                        .build();
                tx.buffer(mutation);
                
                // Insert outbox event in the same transaction
                outboxRepository.insertPendingInTx(tx, outboxId, "Payment", orderId, eventType, topic, key, payloadJson);
                
                return null;
            });
            // 插入成功：按 paymentId 查回来
            return getByPaymentId(paymentId).orElseThrow();
        } catch (com.google.cloud.spanner.SpannerException e) {
            if (e.getErrorCode() == com.google.cloud.spanner.ErrorCode.ALREADY_EXISTS) {
                // 命中 UNIQUE INDEX PaymentsByOrderId：返回已存在的 payment
                return getByOrderId(orderId).orElseThrow();
            }
            throw translator.translate(e);
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }


    @Override
    public Optional<Payment> getByPaymentId(String paymentId) {
        try {
            return inReadOnly(tx -> {
                Key key = Key.of(paymentId);
                Struct row = tx.readRow("Payments", key, 
                        List.of("PaymentId", "OrderId", "Status", "AmountCents", "Currency", "CreatedAt", "UpdatedAt"));
                if (row == null) {
                    return Optional.empty();
                }
                return Optional.<Payment>of(mapRow(row));
            });
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    @Override
    public Optional<Payment> getByOrderId(String orderId) {
        try {
            return inReadOnly(tx -> {
                Statement stmt = Statement.newBuilder(
                                "SELECT PaymentId, OrderId, Status, AmountCents, Currency, CreatedAt, UpdatedAt " +
                                        "FROM Payments WHERE OrderId = @orderId LIMIT 1")
                        .bind("orderId").to(orderId)
                        .build();
                try (ResultSet rs = tx.executeQuery(stmt)) {
                    if (rs.next()) {
                        Struct struct = rs.getCurrentRowAsStruct();
                        return Optional.of(mapRow(struct));
                    }
                    return Optional.empty();
                }
            });
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    private Payment mapRow(Struct row) {
        Timestamp createdAt = row.getTimestamp("CreatedAt");
        Timestamp updatedAt = row.getTimestamp("UpdatedAt");
        return new Payment(
                row.getString("PaymentId"),
                row.getString("OrderId"),
                row.getString("Status"),
                row.getLong("AmountCents"),
                row.getString("Currency"),
                Instant.ofEpochSecond(createdAt.getSeconds(), createdAt.getNanos()),
                Instant.ofEpochSecond(updatedAt.getSeconds(), updatedAt.getNanos())
        );
    }
}

