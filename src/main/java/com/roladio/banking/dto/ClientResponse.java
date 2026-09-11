package com.roladio.banking.dto;

import java.math.BigDecimal;

public record ClientResponse(Long id, String firstName, String lastName, BigDecimal balance) {}
