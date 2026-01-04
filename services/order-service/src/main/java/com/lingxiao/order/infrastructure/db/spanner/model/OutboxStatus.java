package com.lingxiao.order.infrastructure.db.spanner.model;

public enum OutboxStatus {
    NEW,        // 可发送
    SENDING,    // 已被某个 publisher 认领，发送中
    SENT,       // 已成功发送
    DEAD        // 超过最大重试次数，不再重试
}

