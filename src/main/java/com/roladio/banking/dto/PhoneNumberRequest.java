package com.roladio.banking.dto;

import jakarta.validation.constraints.NotBlank;

public record PhoneNumberRequest(
        @NotBlank String phoneNumber
) {}
