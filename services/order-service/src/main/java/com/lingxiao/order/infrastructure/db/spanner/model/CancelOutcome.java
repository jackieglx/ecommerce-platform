package com.lingxiao.order.infrastructure.db.spanner.model;

import java.time.Instant;

public record CancelOutcome(CancelResult result, Instant expireAt) {
}


