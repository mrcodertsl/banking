package com.roladio.banking.dto;

import com.roladio.banking.model.TransactionDirection;
import com.roladio.banking.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        Long id,
        TransactionType type,
        TransactionDirection direction,
        Long fromId,
        String fromName,
        Long toId,
        String toName,
        BigDecimal amount,
        Instant createdAt
) {}