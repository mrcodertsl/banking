package com.roladio.banking.model;

import com.roladio.banking.exceptions.ClientHasBalanceException;
import com.roladio.banking.exceptions.InsufficientFundsException;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.*;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    private String firstName;

    @Setter
    private String lastName;

    private BigDecimal balance;

    @Setter
    private String phoneNumber;

    private boolean closed;

    public void withdraw(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
        balance = balance.subtract(amount);
    }

    public void deposit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public void close() {
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new ClientHasBalanceException(id);
        }
        closed = true;
    }
}
