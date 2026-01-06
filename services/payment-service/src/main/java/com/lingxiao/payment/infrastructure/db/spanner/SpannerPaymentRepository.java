package com.lingxiao.payment.infrastructure.db.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;
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

    public SpannerPaymentRepository(DatabaseClient databaseClient,
                                   SpannerErrorTranslator translator,
                                   TxRunner txRunner) {
        super(databaseClient, translator, txRunner);
    }

    @Override
    public Payment create(String paymentId, String orderId, String status, long amountCents, String currency) {
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
        return getByPaymentId(paymentId).orElseThrow();
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
                return Optional.of(mapRow(row));
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
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            });
        } catch (Exception ex) {
            throw translator.translate(ex);
        }
    }

    private Payment mapRow(Struct row) {
        return new Payment(
                row.getString("PaymentId"),
                row.getString("OrderId"),
                row.getString("Status"),
                row.getLong("AmountCents"),
                row.getString("Currency"),
                row.getTimestamp("CreatedAt").toInstant(),
                row.getTimestamp("UpdatedAt").toInstant()
        );
    }
}

