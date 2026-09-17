package com.roladio.banking.ai;

import com.roladio.banking.dto.ClientResponse;
import com.roladio.banking.dto.TransactionFilter;
import com.roladio.banking.dto.TransactionResponse;
import com.roladio.banking.service.ClientService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class BankingTools {

    private final ClientService clientService;

    public BankingTools(ClientService clientService) {
        this.clientService = clientService;
    }

    @Tool(description = "List all open bank clients with their id, name and current balance.")
    public List<ClientResponse> listClients() {
        return clientService.getAllClients();
    }

    @Tool(description = "Get one bank client by id. Fails if the client does not exist or has been closed.")
    public ClientResponse getClient(
            @ToolParam(description = "The client id") Long clientId) {
        return clientService.getClientById(clientId);
    }

    @Tool(description = """
        Search a client's transfer history. Returns both outgoing and incoming transfers,
        newest first. The direction field says whether money left (OUTGOING) or arrived
        (INCOMING) from this client's point of view. Every filter is optional - leave a
        parameter null to skip that filter.
        """)
    public List<TransactionResponse> searchTransactions(
            @ToolParam(description = "Whose history to search") Long clientId,
            @ToolParam(description = "Only transfers of at least this amount", required = false) BigDecimal minAmount,
            @ToolParam(description = "Only transfers of at most this amount", required = false) BigDecimal maxAmount,
            @ToolParam(description = "Start of the date range, inclusive, as yyyy-MM-dd", required = false) LocalDate from,
            @ToolParam(description = "End of the date range, inclusive, as yyyy-MM-dd", required = false) LocalDate to,
            @ToolParam(description = "Only transfers involving this other client id", required = false) Long counterpartyId) {

        return clientService.searchTransactions(
                clientId,
                new TransactionFilter(minAmount, maxAmount, from, to, counterpartyId));
    }
}