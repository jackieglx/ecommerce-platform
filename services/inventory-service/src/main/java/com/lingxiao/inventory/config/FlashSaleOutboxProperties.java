package com.lingxiao.inventory.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "inventory.flashsale.outbox")
public record FlashSaleOutboxProperties(
        @NotBlank String streamKey,
        @NotBlank String group
) {
}

