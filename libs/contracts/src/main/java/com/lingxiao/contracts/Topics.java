package com.lingxiao.contracts;

public final class Topics {
    private Topics() {}

    public static final String SKU_UPSERTED = "catalog.sku-upserted.v1";
    public static final String FLASH_SALE_RESERVED = "inventory.flashsale-reserved.v1";
    public static final String FLASH_SALE_RESERVED_DLQ = "inventory.flashsale-reserved.dlq.v1";
    public static final String FLASH_SALE_RESERVED_V2 = "inventory.flashsale-reserved.v2";
    public static final String FLASH_SALE_RESERVED_DLQ_V2 = "inventory.flashsale-reserved.dlq.v2";
    public static final String ORDER_TIMEOUT_SCHEDULED = "order.timeout-scheduled.v1";
    public static final String ORDER_CANCELLED = "order.cancelled.v1";
    public static final String INVENTORY_RELEASE_REQUESTED = "inventory.release-requested.v1";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded.v1";
    public static final String ORDER_PAID = "order.paid.v1";
}

