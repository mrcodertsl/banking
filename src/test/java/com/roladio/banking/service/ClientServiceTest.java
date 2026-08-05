package com.roladio.banking.service;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransferRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ClientServiceTest {

    private final ClientService service = new ClientService();

    @Test
    void getAllClients_returnsAllClients() {
        List<ClientResponse> result = service.getAllClients();

        assertThat(result).hasSize(4);
    }

    @Test
    void getAllClients_mapsFieldsCorrectly() {
        List<ClientResponse> result = service.getAllClients();

        ClientResponse firstClient = result.get(0);
        assertThat(firstClient.id()).isEqualTo(1L);
        assertThat(firstClient.name()).isEqualTo("Anna");
        assertThat(firstClient.balance()).isEqualTo(5000.0);
    }

    @Test
    void transfer_negativeAmount() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 2L, -100.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be positive");
    }

    @Test
    void transfer_zeroAmount() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 2L, 0.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be positive");
    }

    @Test
    void transfer_sameAccount() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 1L, 500.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot transfer to the same account");
    }

    @Test
    void transfer_insufficientFunds() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 2L, 9000.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insufficient funds");
    }

    @Test
    void transfer() {
        service.transfer(new TransferRequest(3L, 1L, 1000.0));
        List<ClientResponse> clients = service.getAllClients();

        ClientResponse thirdClient = clients.get(2);
        ClientResponse firstClient = clients.get(0);
        assertThat(thirdClient.balance()).isEqualTo(7000.0);
        assertThat(firstClient.balance()).isEqualTo(6000.0);
    }
}
