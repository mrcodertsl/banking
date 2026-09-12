package com.roladio.banking.service;

import com.roladio.banking.dto.*;
import com.roladio.banking.exceptions.ClientNotFoundException;
import com.roladio.banking.exceptions.InsufficientFundsException;
import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
                new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901"),
                new Client(2L, "Bob", "Jackson", BigDecimal.valueOf(1200), "+12345678902")
        ));

        List<ClientResponse> result = service.getAllClients();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).firstName()).isEqualTo("Anna");
        assertThat(result.get(1).balance()).isEqualByComparingTo("1200");
    }

    @Test
    void getAllClients_mapsFieldsCorrectly() {
        when(repository.findAll()).thenReturn(List.of(
                new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901"),
                new Client(2L, "Bob", "Jackson", BigDecimal.valueOf(1200), "+12345678902")
        ));

        List<ClientResponse> result = service.getAllClients();

        ClientResponse firstClient = result.getFirst();
        assertThat(firstClient.id()).isEqualTo(1L);
        assertThat(firstClient.firstName()).isEqualTo("Anna");
        assertThat(firstClient.balance()).isEqualByComparingTo("5000");
    }

    @Test
    void transfer_sameAccount() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 1L, BigDecimal.valueOf(500))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot transfer to the same account");
    }

    @Test
    void transfer_insufficientFunds() {
        when(repository.findById(1L))
                .thenReturn(Optional.of(new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901")));
        when(repository.findById(2L))
                .thenReturn(Optional.of(new Client(2L, "Bob", "Jackson", BigDecimal.valueOf(1200), "+12345678902")));

        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 2L, BigDecimal.valueOf(9000))))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");
    }

    @Test
    void transfer() {
        Client from = new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901");
        Client to   = new Client(2L, "Bob", "Jackson", BigDecimal.valueOf(1200), "+12345678902");
        when(repository.findById(2L)).thenReturn(Optional.of(from));
        when(repository.findById(1L)).thenReturn(Optional.of(to));

        service.transfer(new TransferRequest(2L, 1L, BigDecimal.valueOf(50)));

        assertThat(from.getBalance()).isEqualByComparingTo("4950");
        assertThat(to.getBalance()).isEqualByComparingTo("1250");

        verify(repository).save(from);
        verify(repository).save(to);
    }

    @Test
    void createClient_returnedSavedClientWithId() {
        when(repository.save(any(Client.class)))
                .thenReturn(new Client(5L,
                        "John",
                        "Doe",
                        BigDecimal.valueOf(750),
                        "+15551234567"));

        ClientResponse response = service.createClient(
                new ClientRequest("John", "Doe", BigDecimal.valueOf(750), "+15551234567")
        );

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.balance()).isEqualByComparingTo("750");
    }

    @Test
    void getClientByIdTest() {
        when(repository.findById(1L))
                .thenReturn(Optional.of(new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901")));

        ClientResponse response = service.getClientById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.firstName()).isEqualTo("Anna");
        assertThat(response.lastName()).isEqualTo("Groban");
        assertThat(response.balance()).isEqualByComparingTo("5000");
    }

    @Test
    void getClientById_whenNotFound_throwsClientNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClientById(99L))
                .isInstanceOf(ClientNotFoundException.class)
                .hasMessage("Client not found: 99");
    }

    @Test
    void updateClientsPhoneNumberTest() {
        Client client = new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901");
        when(repository.findById(1L)).thenReturn(Optional.of(client));

        service.updatePhoneNumber(1L, new PhoneNumberRequest("+00000000000"));

        assertThat(client.getPhoneNumber()).isEqualTo("+00000000000");

        verify(repository).save(client);
    }

    @Test
    void updateClientsLastNameTest() {
        Client client = new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901");
        when(repository.findById(1L)).thenReturn(Optional.of(client));

        service.updateLastName(1L, new LastNameRequest("Test"));

        assertThat(client.getLastName()).isEqualTo("Test");

        verify(repository).save(client);
    }

    @Test
    void updateClient() {
        Client client = new Client(1L, "Anna", "Groban", BigDecimal.valueOf(5000), "+12345678901");
        when(repository.findById(1L)).thenReturn(Optional.of(client));

        service.updateClient(1L, new ClientRequest("FN", "LN", BigDecimal.valueOf(1000), "+123"));

        assertThat(client.getFirstName()).isEqualTo("FN");
        assertThat(client.getLastName()).isEqualTo("LN");
        assertThat(client.getBalance()).isEqualByComparingTo("1000");
        assertThat(client.getPhoneNumber()).isEqualTo("+123");
    }
}
