package com.roladio.banking.dto;

import com.roladio.banking.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        Long id,
        TransactionType type,
        Long fromId,
        String fromName,
        Long toId,
        String toName,
        BigDecimal amount,
        Instant createdAt
) {}