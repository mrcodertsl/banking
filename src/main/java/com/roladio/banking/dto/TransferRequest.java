package com.roladio.banking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull Long fromId,
        @NotNull Long toId,
        @NotNull @Positive BigDecimal amount
) {}
