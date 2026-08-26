package com.roladio.banking.controller;

import com.roladio.banking.dto.*;
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

    @GetMapping("/{id}")
    public ClientResponse getClientById(@PathVariable Long id) {
        return clientService.getClientById(id);
    }

    @PostMapping("/transfer")
    public void transfer(@RequestBody TransferRequest request) {
        clientService.transfer(request);
    }

    @PatchMapping("/{id}/phoneNumber")
    public void updatePhoneNumber(@PathVariable Long id,
                                  @RequestBody PhoneNumberRequest request) {
        clientService.updatePhoneNumber(id, request);
    }

    @PatchMapping("/{id}/lastName")
    public void updateLastName(@PathVariable Long id,
                               @RequestBody LastNameRequest request) {
        clientService.updateLastName(id, request);
    }

    @PutMapping("/{id}/update")
    public void updateClient(@PathVariable Long id,
                             @RequestBody ClientRequest request) {
        clientService.updateClient(id, request);
    }
}
