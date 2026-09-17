package com.roladio.banking.service;

import com.roladio.banking.dto.*;
import com.roladio.banking.exceptions.ClientNotFoundException;
import com.roladio.banking.model.Client;
import com.roladio.banking.model.Transaction;
import com.roladio.banking.model.TransactionDirection;
import com.roladio.banking.repository.ClientRepository;
import com.roladio.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ClientService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");
    private static final Instant MIN_INSTANT = Instant.EPOCH;
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    private final ClientRepository clientRepository;
    private final TransactionRepository transactionRepository;

    public ClientService(ClientRepository clientRepository, TransactionRepository transactionRepository) {
        this.clientRepository = clientRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<ClientResponse> getAllClients() {
        return clientRepository.findAllByClosedFalse().stream()
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
        client.setPhoneNumber(request.phoneNumber());
    }

    @Transactional
    public void transfer(TransferRequest request) {
        Long fromId = request.fromId();;
        Long toId = request.toId();

        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        Client from;
        Client to;

        if (fromId < toId) {
            from = lockClientById(fromId);
            to = lockClientById(toId);
        } else {
            to = lockClientById(toId);
            from = lockClientById(fromId);
        }

        from.withdraw(request.amount());
        to.deposit(request.amount());

        transactionRepository.save(Transaction.transfer(from, to, request.amount()));
    }

    @Transactional
    public ClientResponse createClient(ClientRequest request) {
        Client client = new Client(null,
                request.firstName(),
                request.lastName(),
                request.balance(),
                request.phoneNumber(),
                false);

        Client saved = clientRepository.save(client);

        return toResponse(saved);
    }

    @Transactional
    public void closeClient(Long id) {
        Client client = findClientById(id);
        client.close();
    }

    public List<TransactionResponse> getClientHistory(Long id) {
        findClientById(id);

        return transactionRepository.findHistoryForClient(id).stream()
                .map(transaction -> toTransactionResponse(transaction, id))
                .collect(Collectors.toList());
    }

    public List<TransactionResponse> searchTransactions(Long id, TransactionFilter filter) {
        findClientById(id);

        BigDecimal minAmount = filter.minAmount() != null ? filter.minAmount() : BigDecimal.ZERO;
        BigDecimal maxAmount = filter.maxAmount() != null ? filter.maxAmount() : MAX_AMOUNT;
        Instant fromInstant = filter.from() != null ? toStartOfDay(filter.from()) : MIN_INSTANT;
        Instant toInstant = filter.to() != null ? toStartOfNextDay(filter.to()) : MAX_INSTANT;
        Long counterpartyId = filter.counterpartyId() != null ? filter.counterpartyId() : id;

        return transactionRepository.search(id, minAmount, maxAmount, fromInstant, toInstant, counterpartyId)
                .stream()
                .map(transaction -> toTransactionResponse(transaction, id))
                .collect(Collectors.toList());
    }

    private Instant toStartOfDay(LocalDate date) {
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private Instant toStartOfNextDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private Client findClientById(Long id) {
        return clientRepository.findByIdAndClosedFalse(id)
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

    private TransactionResponse toTransactionResponse(Transaction transaction, Long viewerId) {
        Client from = transaction.getFrom();
        Client to = transaction.getTo();

        TransactionDirection direction = from.getId().equals(viewerId)
                ? TransactionDirection.OUTGOING
                : TransactionDirection.INCOMING;

        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                direction,
                from.getId(),
                from.getFirstName() + " " + from.getLastName(),
                to.getId(),
                to.getFirstName() + " " + to.getLastName(),
                transaction.getAmount(),
                transaction.getCreatedAt()
        );
    }

    private Client lockClientById(Long id) {
        return clientRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ClientNotFoundException(id));
    }

}
