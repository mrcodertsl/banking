package com.roladio.banking.service;

import com.roladio.banking.dto.*;
import com.roladio.banking.exceptions.ClientHasBalanceException;
import com.roladio.banking.exceptions.ClientNotFoundException;
import com.roladio.banking.exceptions.InsufficientFundsException;
import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import com.roladio.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ClientServiceTest {

    private final ClientRepository repository = mock(ClientRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final ClientService service = new ClientService(repository, transactionRepository);

    @Test
    void getAllClients_returnsAllClients() {
        when(repository.findAllByClosedFalse()).thenReturn(List.of(
                new Client(1L, "Anna", "Groban",
                        BigDecimal.valueOf(5000), "+12345678901", false),
                new Client(2L, "Bob", "Jackson",
                        BigDecimal.valueOf(1200), "+12345678902", false)
        ));

        List<ClientResponse> result = service.getAllClients();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).firstName()).isEqualTo("Anna");
        assertThat(result.get(1).balance()).isEqualByComparingTo("1200");
    }

    @Test
    void getAllClients_mapsFieldsCorrectly() {
        when(repository.findAllByClosedFalse()).thenReturn(List.of(
                new Client(1L, "Anna", "Groban",
                        BigDecimal.valueOf(5000), "+12345678901", false),
                new Client(2L, "Bob", "Jackson",
                        BigDecimal.valueOf(1200), "+12345678902", false)
        ));

        List<ClientResponse> result = service.getAllClients();

        ClientResponse firstClient = result.getFirst();
        assertThat(firstClient.id()).isEqualTo(1L);
        assertThat(firstClient.firstName()).isEqualTo("Anna");
        assertThat(firstClient.balance()).isEqualByComparingTo("5000");
    }

    @Test
    void transfer_whenSameAccount_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 1L, BigDecimal.valueOf(500))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot transfer to the same account");
    }

    @Test
    void transfer_whenInsufficientFunds_throwsInsufficientFunds() {
        when(repository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(new Client(1L, "Anna", "Groban",
                        BigDecimal.valueOf(5000), "+12345678901", false)));
        when(repository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(new Client(2L, "Bob", "Jackson",
                        BigDecimal.valueOf(1200), "+12345678902", false)));

        assertThatThrownBy(() -> service.transfer(new TransferRequest(1L, 2L, BigDecimal.valueOf(9000))))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");
    }

    @Test
    void transfer_movesMoneyBetweenAccounts() {
        Client from = new Client(1L, "Anna", "Groban",
                BigDecimal.valueOf(5000), "+12345678901", false);
        Client to   = new Client(2L, "Bob", "Jackson",
                BigDecimal.valueOf(1200), "+12345678902", false);

        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(from));
        when(repository.findByIdForUpdate(2L)).thenReturn(Optional.of(to));

        service.transfer(new TransferRequest(1L, 2L, BigDecimal.valueOf(50)));

        assertThat(from.getBalance()).isEqualByComparingTo("4950");
        assertThat(to.getBalance()).isEqualByComparingTo("1250");
    }

    @Test
    void createClient_returnedSavedClientWithId() {
        when(repository.save(any(Client.class)))
                .thenReturn(new Client(5L,
                        "John",
                        "Doe",
                        BigDecimal.valueOf(750),
                        "+15551234567",
                        false));

        ClientResponse response = service.createClient(
                new ClientRequest("John", "Doe", BigDecimal.valueOf(750), "+15551234567")
        );

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.balance()).isEqualByComparingTo("750");
    }

    @Test
    void getClientById_returnsClient() {
        when(repository.findByIdAndClosedFalse(1L))
                .thenReturn(Optional.of(new Client(1L, "Anna", "Groban",
                        BigDecimal.valueOf(5000), "+12345678901", false)));

        ClientResponse response = service.getClientById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.firstName()).isEqualTo("Anna");
        assertThat(response.lastName()).isEqualTo("Groban");
        assertThat(response.balance()).isEqualByComparingTo("5000");
    }

    @Test
    void getClientById_whenNotFound_throwsClientNotFound() {
        when(repository.findByIdAndClosedFalse(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getClientById(99L))
                .isInstanceOf(ClientNotFoundException.class)
                .hasMessage("Client not found: 99");
    }

    @Test
    void updatePhoneNumber_updatesPhoneNumber() {
        Client client = new Client(1L, "Anna", "Groban",
                BigDecimal.valueOf(5000), "+12345678901", false);
        when(repository.findByIdAndClosedFalse(1L)).thenReturn(Optional.of(client));

        service.updatePhoneNumber(1L, new PhoneNumberRequest("+00000000000"));

        assertThat(client.getPhoneNumber()).isEqualTo("+00000000000");
    }

    @Test
    void updateLastName_updatesLastName() {
        Client client = new Client(1L, "Anna", "Groban",
                BigDecimal.valueOf(5000), "+12345678901", false);
        when(repository.findByIdAndClosedFalse(1L)).thenReturn(Optional.of(client));

        service.updateLastName(1L, new LastNameRequest("Test"));

        assertThat(client.getLastName()).isEqualTo("Test");
    }

    @Test
    void updateClient_updatesAllFields() {
        Client client = new Client(1L, "Anna", "Groban",
                BigDecimal.valueOf(5000), "+12345678901", false);
        when(repository.findByIdAndClosedFalse(1L)).thenReturn(Optional.of(client));

        service.updateClient(1L, new ClientRequest("FN", "LN", BigDecimal.valueOf(1000), "+123"));

        assertThat(client.getFirstName()).isEqualTo("FN");
        assertThat(client.getLastName()).isEqualTo("LN");
        assertThat(client.getPhoneNumber()).isEqualTo("+123");
    }

    @Test
    void withdraw_whenInsufficientFunds_throwsAndLeavesBalanceUnchanged() {
        Client client = new Client(1L, "Anna", "Groban",
                BigDecimal.valueOf(100), "+12345678901", false);

        assertThatThrownBy(() -> client.withdraw(BigDecimal.valueOf(200)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(client.getBalance()).isEqualByComparingTo("100");
    }

    @Test
    void closeClient_whenBalanceIsZero_marksClientClosed() {
        Client client = new Client(1L, "Anna", "Groban",
                BigDecimal.ZERO, "+12345678901", false);
        when(repository.findByIdAndClosedFalse(1L)).thenReturn(Optional.of(client));

        service.closeClient(1L);

        assertThat(client.isClosed()).isTrue();
    }

    @Test
    void closeClient_whenBalanceIsNotZero_throwsAndLeavesClientOpen() {
        Client client = new Client(1L, "Anna", "Groban",
                BigDecimal.valueOf(100), "+12345678901", false);
        when(repository.findByIdAndClosedFalse(1L)).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> service.closeClient(1L))
                .isInstanceOf(ClientHasBalanceException.class);

        assertThat(client.isClosed()).isFalse();
    }
}
