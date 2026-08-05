package com.roladio.banking.service;

import com.roladio.banking.dto.ClientDto;
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

    public List<ClientDto> getAllClients() {
        return clients.stream()
                .map(c -> new ClientDto(c.getId(), c.getName(), c.getBalance()))
                .collect(Collectors.toList());
    }
}
