package com.roladio.banking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionFilter(
        BigDecimal minAmount,
        BigDecimal maxAmount,
        LocalDate from,
        LocalDate to,
        Long counterpartyId
) {}