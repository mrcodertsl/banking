package com.roladio.banking.service;

import com.roladio.banking.dto.*;
import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .map(c -> new ClientResponse(c.getId(), c.getFirstName(), c.getLastName(), c.getBalance()))
                .collect(Collectors.toList());
    }

    public ClientResponse getClientById(Long id) {
        Client client = findClientById(id);
        return new ClientResponse(client.getId(), client.getFirstName(), client.getLastName(), client.getBalance());
    }

    @Transactional
    public void updatePhoneNumber(Long id, PhoneNumberRequest request) {
        Client client = findClientById(id);
        client.setPhoneNumber(request.phoneNumber());
        clientRepository.save(client);
    }

    @Transactional
    public void updateLastName(Long id, LastNameRequest request) {
        Client client = findClientById(id);
        client.setLastName(request.lastName());
        clientRepository.save(client);
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
        if (request.amount() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (request.fromId().equals(request.toId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        Client from = findClientById(request.fromId());
        Client to = findClientById(request.toId());

        if (from.getBalance() < request.amount()) {
            throw new IllegalStateException("Insufficient funds");
        }

        from.setBalance(from.getBalance() - request.amount());
        to.setBalance(to.getBalance() + request.amount());

        clientRepository.save(from);
        clientRepository.save(to);
    }

    private Client findClientById(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
    }

}
