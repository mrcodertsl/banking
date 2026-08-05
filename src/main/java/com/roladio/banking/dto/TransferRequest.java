package com.roladio.banking.dto;

public record TransferRequest(Long fromId, Long toId, double amount) {}
