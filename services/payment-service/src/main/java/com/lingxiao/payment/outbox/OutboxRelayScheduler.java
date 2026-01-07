package com.lingxiao.payment.outbox;

import com.lingxiao.payment.domain.OutboxEvent;
import com.lingxiao.payment.infrastructure.db.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 10;
    private static final long LEASE_DURATION_SECONDS = 300; // 5 minutes
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String processorId;

    public OutboxRelayScheduler(OutboxRepository outboxRepository,
                                KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;

        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            hostname = "unknown";
        }
        this.processorId = "processor-" + hostname + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${payment.outbox.relay.interval:5000}")
    public void relayPendingEvents() {
        List<OutboxEvent> claimedEvents;
        try {
            claimedEvents = outboxRepository.claimPendingBatch(BATCH_SIZE, processorId, LEASE_DURATION_SECONDS);
        } catch (Exception e) {
            log.error("Error claiming outbox events", e);
            return;
        }

        if (claimedEvents.isEmpty()) return;

        for (OutboxEvent event : claimedEvents) {
            // attemptCount 已在 claim 时 +1
            if (event.attemptCount() > MAX_RETRIES) {
                outboxRepository.markFailed(event.id(), processorId, event.attemptCount(),
                        "Exceeded max retries before send", true);
                continue;
            }

            try {
                kafkaTemplate.send(event.topic(), event.kafkaKey(), event.payloadJson())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                outboxRepository.markSent(event.id(), processorId);

                log.debug("Outbox sent. id={} type={} topic={} key={} attempts={}",
                        event.id(), event.eventType(), event.topic(), event.kafkaKey(), event.attemptCount());
            } catch (Exception ex) {
                boolean permanent = event.attemptCount() >= MAX_RETRIES;
                String msg = ex.getMessage();

                outboxRepository.markFailed(event.id(), processorId, event.attemptCount(), msg, permanent);

                if (permanent) {
                    log.error("Outbox failed permanently. id={} attempts={}", event.id(), event.attemptCount(), ex);
                } else {
                    log.warn("Outbox send failed, will retry. id={} attempts={}", event.id(), event.attemptCount(), ex);
                }
            }
        }
    }
}
