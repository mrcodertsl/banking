package com.roladio.banking.controller;

import com.roladio.banking.dto.*;
import com.roladio.banking.service.ClientService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@Valid @RequestBody TransferRequest request) {
        clientService.transfer(request);
    }

    @PostMapping
    public ResponseEntity<ClientResponse> createClient(@Valid @RequestBody ClientRequest request) {
        ClientResponse created = clientService.createClient(request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PatchMapping("/{id}/phoneNumber")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updatePhoneNumber(@PathVariable Long id,
                                  @Valid @RequestBody PhoneNumberRequest request) {
        clientService.updatePhoneNumber(id, request);
    }

    @PatchMapping("/{id}/lastName")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLastName(@PathVariable Long id,
                               @RequestBody LastNameRequest request) {
        clientService.updateLastName(id, request);
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateClient(@PathVariable Long id,
                             @Valid @RequestBody ClientRequest request) {
        clientService.updateClient(id, request);
    }
}
