package com.roladio.banking.service;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransferRequest;
import com.roladio.banking.model.Client;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ClientService {

    private final List<Client> clients = List.of(
            new Client(1L, "Anna", "Berlin", 5000.0),
            new Client(2L, "Bob",  "Toronto",  1200.0),
            new Client(3L, "Cara", "Lisbon", 8000.0),
            new Client(4L, "Dan",  "Melbourne",   300.0)
    );

    public List<ClientResponse> getAllClients() {
        return clients.stream()
                .map(c -> new ClientResponse(c.getId(), c.getName(), c.getBalance()))
                .collect(Collectors.toList());
    }

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
    }

    private Client findClientById(Long id) {
        return clients.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
    }
}
