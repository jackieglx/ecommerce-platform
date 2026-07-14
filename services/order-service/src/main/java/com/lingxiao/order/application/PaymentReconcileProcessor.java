package com.lingxiao.order.application;

import com.lingxiao.order.infrastructure.db.spanner.OrderRepository;
import com.lingxiao.order.infrastructure.db.spanner.OrderRepository.PendingPaymentReconcileResult;
import com.lingxiao.order.infrastructure.redis.PaymentReconcileQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PaymentReconcileProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileProcessor.class);

    private final PaymentReconcileQueue queue;
    private final OrderRepository repository;

    private final int claimBatchSize;
    private final long staleMs;
    private final int reclaimBatchSize;
    private final long maxAttempts;
    private final long baseBackoffMs;
    private final long backoffCapMs;

    public PaymentReconcileProcessor(
            PaymentReconcileQueue queue,
            OrderRepository repository,
            @Value("${order.payment-reconcile.claim-batch-size:50}") int claimBatchSize,
            @Value("${order.payment-reconcile.stale-ms:30000}") long staleMs,
            @Value("${order.payment-reconcile.reclaim-batch-size:100}") int reclaimBatchSize,
            @Value("${order.payment-reconcile.max-attempts:10}") long maxAttempts,
            @Value("${order.payment-reconcile.base-backoff-ms:500}") long baseBackoffMs,
            @Value("${order.payment-reconcile.backoff-cap-ms:60000}") long backoffCapMs
    ) {
        this.queue = queue;
        this.repository = repository;
        this.claimBatchSize = claimBatchSize;
        this.staleMs = staleMs;
        this.reclaimBatchSize = reclaimBatchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        this.backoffCapMs = backoffCapMs;
    }

    @Scheduled(fixedDelayString = "${order.payment-reconcile.claim-interval-ms:1000}")
    public void claimAndProcess() {
        var claim = queue.claimDue(claimBatchSize);
        if (claim.orderIds().isEmpty()) return;

        String token = claim.token();
        Instant now = Instant.now();

        for (String orderId : claim.orderIds()) {
            try {
                PendingPaymentReconcileResult r = repository.reconcilePendingPayment(orderId, now);
                switch (r) {
                    case APPLIED, REFUND_REQUIRED, NO_PENDING, ALREADY_FINAL -> queue.ack(orderId, token);
                    case ORDER_MISSING -> {
                        long attempts = queue.incrementAttempt(orderId);
                        if (attempts >= maxAttempts) {
                            log.error("Payment reconcile exceeded max attempts, giving up orderId={} attempts={}", orderId, attempts);
                            queue.ack(orderId, token);
                            continue;
                        }
                        long backoffMs = computeBackoffMs(attempts);
                        boolean ok = queue.reschedule(orderId, token, now.plusMillis(backoffMs));
                        if (ok) {
                            log.debug("Payment reconcile rescheduled orderId={} attempts={} backoffMs={}", orderId, attempts, backoffMs);
                        } else {
                            // token mismatch: someone else owns it; keep it in processing and let reclaim handle it
                            log.warn("Payment reconcile reschedule token mismatch orderId={} attempts={}", orderId, attempts);
                        }
                    }
                }
            } catch (Exception e) {
                long attempts = queue.incrementAttempt(orderId);
                log.warn("Payment reconcile failed orderId={} attempts={}", orderId, attempts, e);
                if (attempts >= maxAttempts) {
                    queue.ack(orderId, token);
                }
                // don't ack -> allow reclaim to retry
            }
        }
    }

    @Scheduled(fixedDelayString = "${order.payment-reconcile.reclaim-interval-ms:5000}")
    public void reclaimStuck() {
        List<String> reclaimed = queue.reclaim(staleMs, reclaimBatchSize);
        if (!reclaimed.isEmpty()) {
            log.debug("Reclaimed payment reconciles back to ready count={}", reclaimed.size());
        }
    }

    private long computeBackoffMs(long attempt) {
        // attempt starts from 1: base, 2*base, 4*base...
        long shift = Math.min(Math.max(attempt - 1, 0), 10);
        long backoff = baseBackoffMs * (1L << shift);
        return Math.min(backoff, backoffCapMs);
    }
}

