package com.roladio.banking.service;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransactionResponse;
import com.roladio.banking.dto.TransferRequest;
import com.roladio.banking.exceptions.ClientNotFoundException;
import com.roladio.banking.exceptions.InsufficientFundsException;
import com.roladio.banking.model.TransactionDirection;
import com.roladio.banking.repository.ClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

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

    @Test
    void closeClient_removesClientFromListAndMakesItUnavailable() {
        BigDecimal balance = clientRepository.findById(3L).orElseThrow().getBalance();
        clientService.transfer(new TransferRequest(3L, 1L, balance));

        int before = clientService.getAllClients().size();

        clientService.closeClient(3L);

        assertThat(clientService.getAllClients()).hasSize(before - 1);

        assertThatThrownBy(() -> clientService.getClientById(3L))
                .isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void getClientHistory_returnsTransferForBothParticipants() {
        clientService.transfer(new TransferRequest(1L, 2L, new BigDecimal("25.00")));

        List<TransactionResponse> fromHistory = clientService.getClientHistory(1L);
        List<TransactionResponse> toHistory = clientService.getClientHistory(2L);

        assertThat(fromHistory).isNotEmpty();
        assertThat(toHistory).isNotEmpty();

        TransactionResponse latest = fromHistory.getFirst();
        assertThat(latest.fromId()).isEqualTo(1L);
        assertThat(latest.toId()).isEqualTo(2L);
        assertThat(latest.amount()).isEqualByComparingTo("25.00");
        assertThat(latest.createdAt()).isNotNull();
        assertThat(latest.fromName()).isNotBlank();
        assertThat(latest.direction()).isEqualTo(TransactionDirection.OUTGOING);

        TransactionResponse sameTransferSeenByRecipient = toHistory.getFirst();
        assertThat(sameTransferSeenByRecipient.id()).isEqualTo(latest.id());
        assertThat(sameTransferSeenByRecipient.direction()).isEqualTo(TransactionDirection.INCOMING);
    }

    @Test
    void getClientHistory_whenClientDoesNotExist_throwsClientNotFound() {
        assertThatThrownBy(() -> clientService.getClientHistory(999L))
                .isInstanceOf(ClientNotFoundException.class);
    }
}