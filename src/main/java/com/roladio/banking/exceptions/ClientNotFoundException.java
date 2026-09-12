package com.roladio.banking.exceptions;

public class ClientNotFoundException extends RuntimeException {

    public ClientNotFoundException(Long id) {
        super("Client not found: " + id);
    }
}
