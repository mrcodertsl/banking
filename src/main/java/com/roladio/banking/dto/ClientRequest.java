package com.roladio.banking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record ClientRequest(
        @NotBlank String firstName,
        String lastName,
        @NotNull @PositiveOrZero BigDecimal balance,
        String phoneNumber
) {}
