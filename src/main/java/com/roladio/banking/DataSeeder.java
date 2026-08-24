package com.roladio.banking;

import com.roladio.banking.model.Client;
import com.roladio.banking.repository.ClientRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final ClientRepository clientRepository;

    public DataSeeder(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (clientRepository.count() == 0) {
            clientRepository.save(new Client(1L, "Anna", "Berlin", 5000.0));
            clientRepository.save(new Client(2L, "Bob",  "Toronto",   1200.0));
            clientRepository.save(new Client(3L, "Cara", "Lisbon",    8000.0));
            clientRepository.save(new Client(4L, "Dan",  "Melbourne",  300.0));
        }
    }
}
