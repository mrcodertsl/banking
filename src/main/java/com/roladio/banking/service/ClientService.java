package com.roladio.banking.service;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransferRequest;
import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

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
                .map(c -> new ClientResponse(c.getId(), c.getName(), c.getBalance()))
                .collect(Collectors.toList());
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
