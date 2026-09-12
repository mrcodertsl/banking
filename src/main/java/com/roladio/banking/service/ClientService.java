package com.roladio.banking.service;

import com.roladio.banking.dto.*;
import com.roladio.banking.exceptions.ClientNotFoundException;
import com.roladio.banking.exceptions.InsufficientFundsException;
import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ClientService {

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    public List<ClientResponse> getAllClients() {
        return clientRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ClientResponse getClientById(Long id) {
        return toResponse(findClientById(id));
    }

    @Transactional
    public void updatePhoneNumber(Long id, PhoneNumberRequest request) {
        Client client = findClientById(id);
        client.setPhoneNumber(request.phoneNumber());
    }

    @Transactional
    public void updateLastName(Long id, LastNameRequest request) {
        Client client = findClientById(id);
        client.setLastName(request.lastName());
    }

    @Transactional
    public void updateClient(Long id, ClientRequest request) {
        Client client = findClientById(id);
        client.setFirstName(request.firstName());
        client.setLastName(request.lastName());
        client.setBalance(request.balance());
        client.setPhoneNumber(request.phoneNumber());
    }

    @Transactional
    public void transfer(TransferRequest request) {
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (request.fromId().equals(request.toId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        Client from = findClientById(request.fromId());
        Client to = findClientById(request.toId());

        if (from.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException();
        }

        from.setBalance(from.getBalance().subtract(request.amount()));
        to.setBalance(to.getBalance().add(request.amount()));
    }

    @Transactional
    public ClientResponse createClient(ClientRequest request) {
        Client client = new Client(null,
                request.firstName(),
                request.lastName(),
                request.balance(),
                request.phoneNumber());

        Client saved = clientRepository.save(client);

        return toResponse(saved);
    }

    private Client findClientById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new ClientNotFoundException(id));
    }

    private ClientResponse toResponse(Client client) {
        return new ClientResponse(
                client.getId(),
                client.getFirstName(),
                client.getLastName(),
                client.getBalance()
        );
    }

}
