package com.roladio.banking.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Client {

    @Id
    private Long id;
    private String firstName;
    private String lastName;
    private double balance;
    private String phoneNumber;
}
