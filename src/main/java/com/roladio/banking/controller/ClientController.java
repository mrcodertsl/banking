package com.roladio.banking.controller;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransferRequest;
import com.roladio.banking.service.ClientService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/clients")
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @GetMapping
    public List<ClientResponse> getAllClients() {
        return clientService.getAllClients();
    }

    @PostMapping("/transfer")
    public void transfer(@RequestBody TransferRequest request) {
        clientService.transfer(request);
    }
}
