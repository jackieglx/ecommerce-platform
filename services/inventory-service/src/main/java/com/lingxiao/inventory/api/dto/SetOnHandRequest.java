package com.lingxiao.inventory.api.dto;

import jakarta.validation.constraints.Min;

public record SetOnHandRequest(@Min(0) long onHand) {
}


