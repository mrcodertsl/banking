package com.roladio.banking.ai;

import com.roladio.banking.dto.TransactionFilter;
import com.roladio.banking.exceptions.QueryParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class TransactionQueryParser {

    private static final String SYSTEM_PROMPT = """
        You turn a plain-language request about bank transfer history into a filter.
        Today is %s.

        Fill in ONLY the fields the user explicitly asked for.
        Every field the user did not mention MUST be null.
        Never fill a date range unless the user mentioned time.

        Examples:

        "transfers over 1000"
        -> minAmount 1000, maxAmount null, from null, to null, counterpartyId null

        "small payments under 50"
        -> minAmount null, maxAmount 50, from null, to null, counterpartyId null

        "transfers in the last week"
        -> minAmount null, maxAmount null, from seven days before today, to today, counterpartyId null

        "big transfers last month"
        -> minAmount 1000, maxAmount null, from first day of last month, to last day of last month, counterpartyId null

        "transfers with client 3"
        -> minAmount null, maxAmount null, from null, to null, counterpartyId 3

        "everything"
        -> all fields null

        Dates use ISO format yyyy-MM-dd.
        """;

    private final ChatClient chatClient;

    private static final Logger log = LoggerFactory.getLogger(TransactionQueryParser.class);

    public TransactionQueryParser(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public TransactionFilter parse(String query) {
        try {
            TransactionFilter filter = chatClient.prompt()
                    .system(SYSTEM_PROMPT.formatted(LocalDate.now()))
                    .user(query)
                    .call()
                    .entity(TransactionFilter.class);

            log.info("Parsed query [{}] into {}", query, filter);

            return filter;
        } catch (Exception e) {
            log.warn("Could not parse query [{}]", query, e);
            throw new QueryParsingException(e);
        }
    }
}