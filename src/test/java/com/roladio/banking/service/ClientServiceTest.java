package com.roladio.banking.service;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransferRequest;
import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class ClientServiceTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final ClientService service = new ClientService(repository);

    @Test
    void getAllClients_returnsAllClients() {
        when(repository.findAll()).thenReturn(List.of(
                new Client(1L, "Anna", "Berlin", 5000.0),
                new Client(2L, "Bob", "Toronto", 1200.0)
        ));

        List<ClientResponse> result = service.getAllClients();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("Anna");
        assertThat(result.get(1).balance()).isEqualTo(1200.0);
    }

    @Test
    void getAllClients_mapsFieldsCorrectly() {
        when(repository.findAll()).thenReturn(List.of(
                new Client(1L, "Anna", "Berlin", 5000.0),
                new Client(2L, "Bob", "Toronto", 1200.0)
        ));

        List<ClientResponse> result = service.getAllClients();

        ClientResponse firstClient = result.getFirst();
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
        when(repository.findById(1L)).thenReturn(Optional.of(new Client(1L, "Anna", "X", 100.0)));
        when(repository.findById(2L)).thenReturn(Optional.of(new Client(2L, "Bob", "Y", 500.0)));

        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 2L, 9000.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insufficient funds");
    }

    @Test
    void transfer() {
        Client from = new Client(2L, "Bob", "Y", 500.0);
        Client to   = new Client(1L, "Anna", "X", 100.0);
        when(repository.findById(2L)).thenReturn(Optional.of(from));
        when(repository.findById(1L)).thenReturn(Optional.of(to));

        service.transfer(new TransferRequest(2L, 1L, 50.0));

        assertThat(from.getBalance()).isEqualTo(450.0);
        assertThat(to.getBalance()).isEqualTo(150.0);

        verify(repository).save(from);
        verify(repository).save(to);
    }
}
