package com.roladio.banking.service;

import com.roladio.banking.dto.*;
import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ClientServiceTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final ClientService service = new ClientService(repository);

    @Test
    void getAllClients_returnsAllClients() {
        when(repository.findAll()).thenReturn(List.of(
                new Client(1L, "Anna", "Groban", 5000.0, "+12345678901"),
                new Client(2L, "Bob", "Jackson", 1200.0, "+12345678902")
        ));

        List<ClientResponse> result = service.getAllClients();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).firstName()).isEqualTo("Anna");
        assertThat(result.get(1).balance()).isEqualTo(1200.0);
    }

    @Test
    void getAllClients_mapsFieldsCorrectly() {
        when(repository.findAll()).thenReturn(List.of(
                new Client(1L, "Anna", "Groban", 5000.0, "+12345678901"),
                new Client(2L, "Bob", "Jackson", 1200.0, "+12345678902")
        ));

        List<ClientResponse> result = service.getAllClients();

        ClientResponse firstClient = result.getFirst();
        assertThat(firstClient.id()).isEqualTo(1L);
        assertThat(firstClient.firstName()).isEqualTo("Anna");
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
        when(repository.findById(1L))
                .thenReturn(Optional.of(new Client(1L, "Anna", "Groban", 5000.0, "+12345678901")));
        when(repository.findById(2L))
                .thenReturn(Optional.of(new Client(2L, "Bob", "Jackson", 1200.0, "+12345678902")));

        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 2L, 9000.0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insufficient funds");
    }

    @Test
    void transfer() {
        Client from = new Client(1L, "Anna", "Groban", 5000.0, "+12345678901");
        Client to   = new Client(2L, "Bob", "Jackson", 1200.0, "+12345678902");
        when(repository.findById(2L)).thenReturn(Optional.of(from));
        when(repository.findById(1L)).thenReturn(Optional.of(to));

        service.transfer(new TransferRequest(2L, 1L, 50.0));

        assertThat(from.getBalance()).isEqualTo(4950.0);
        assertThat(to.getBalance()).isEqualTo(1250.0);

        verify(repository).save(from);
        verify(repository).save(to);
    }

    @Test
    void getClientByIdTest() {
        when(repository.findById(1L))
                .thenReturn(Optional.of(new Client(1L, "Anna", "Groban", 5000.0, "+12345678901")));

        ClientResponse response = service.getClientById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.firstName()).isEqualTo("Anna");
        assertThat(response.lastName()).isEqualTo("Groban");
        assertThat(response.balance()).isEqualTo(5000.0);
    }

    @Test
    void updateClientsPhoneNumberTest() {
        Client client = new Client(1L, "Anna", "Groban", 5000.0, "+12345678901");
        when(repository.findById(1L)).thenReturn(Optional.of(client));

        service.updatePhoneNumber(1L, new PhoneNumberRequest("+00000000000"));

        assertThat(client.getPhoneNumber()).isEqualTo("+00000000000");

        verify(repository).save(client);
    }

    @Test
    void updateClientsLastNameTest() {
        Client client = new Client(1L, "Anna", "Groban", 5000.0, "+12345678901");
        when(repository.findById(1L)).thenReturn(Optional.of(client));

        service.updateLastName(1L, new LastNameRequest("Test"));

        assertThat(client.getLastName()).isEqualTo("Test");

        verify(repository).save(client);
    }

    @Test
    void updateClient() {
        Client client = new Client(1L, "Anna", "Groban", 5000.0, "+12345678901");
        when(repository.findById(1L)).thenReturn(Optional.of(client));

        service.updateClient(1L, new ClientRequest("FN", "LN", 1000.0, "+123"));

        assertThat(client.getFirstName()).isEqualTo("FN");
        assertThat(client.getLastName()).isEqualTo("LN");
        assertThat(client.getBalance()).isEqualTo(1000.0);
        assertThat(client.getPhoneNumber()).isEqualTo("+123");
    }
}
