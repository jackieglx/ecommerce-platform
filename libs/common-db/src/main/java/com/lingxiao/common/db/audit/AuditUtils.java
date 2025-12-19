package com.lingxiao.common.db.audit;

import java.time.Instant;

public final class AuditUtils {
    private AuditUtils() {
    }

    public static void touchForCreate(Auditable auditable) {
        Instant now = Instant.now();
        auditable.setCreatedAt(now);
        auditable.setUpdatedAt(now);
    }

    public static void touchForUpdate(Auditable auditable) {
        auditable.setUpdatedAt(Instant.now());
    }
}

