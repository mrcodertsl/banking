package com.roladio.banking.service;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransferRequest;
import com.roladio.banking.exceptions.InsufficientFundsException;
import com.roladio.banking.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class ClientServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ClientService clientService;

    @Autowired
    private ClientRepository clientRepository;

    @Test
    void transfer_movesMoneyAndPersistsBothBalances() {
        BigDecimal fromBefore = clientRepository.findById(1L).orElseThrow().getBalance();
        BigDecimal toBefore = clientRepository.findById(2L).orElseThrow().getBalance();

        clientService.transfer(new TransferRequest(1L, 2L, new BigDecimal("100.00")));

        ClientResponse from = clientService.getClientById(1L);
        ClientResponse to = clientService.getClientById(2L);

        assertThat(from.balance()).isEqualByComparingTo(fromBefore.subtract(new BigDecimal("100.00")));
        assertThat(to.balance()).isEqualByComparingTo(toBefore.add(new BigDecimal("100.00")));
    }

    @Test
    void transfer_whenInsufficientFunds_leavesBothBalancesUnchanged() {
        BigDecimal fromBefore = clientRepository.findById(4L).orElseThrow().getBalance();
        BigDecimal toBefore = clientRepository.findById(1L).orElseThrow().getBalance();

        assertThatThrownBy(() ->
                clientService.transfer(new TransferRequest(4L, 1L, new BigDecimal("100.00"))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(clientRepository.findById(4L).orElseThrow().getBalance())
                .isEqualByComparingTo(fromBefore);
        assertThat(clientRepository.findById(1L).orElseThrow().getBalance())
                .isEqualByComparingTo(toBefore);
    }
}