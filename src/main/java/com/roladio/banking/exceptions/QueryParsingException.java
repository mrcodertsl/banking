package com.roladio.banking.exceptions;

public class QueryParsingException extends RuntimeException {

    public QueryParsingException(Throwable cause) {
        super("Could not interpret the search query", cause);
    }
}