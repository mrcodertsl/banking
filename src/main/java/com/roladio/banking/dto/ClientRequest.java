package com.roladio.banking.dto;

import java.math.BigDecimal;

public record ClientRequest(String firstName, String lastName, BigDecimal balance, String phoneNumber) {}
