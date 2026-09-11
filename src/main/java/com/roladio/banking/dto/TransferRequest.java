package com.roladio.banking.dto;

import java.math.BigDecimal;

public record TransferRequest(Long fromId, Long toId, BigDecimal amount) {}
