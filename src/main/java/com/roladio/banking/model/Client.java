package com.roladio.banking.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Client {
    private long id;
    private String name;
    private String city;
    private double balance;
}
