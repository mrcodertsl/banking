package com.roladio.banking.exceptions;

public class ClientHasBalanceException extends RuntimeException {

    public ClientHasBalanceException(Long id) {
        super("Cannot close client with a non-zero balance: " + id);
    }
}