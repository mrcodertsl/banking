package com.roladio.banking.controller;

import com.roladio.banking.ai.TransactionQueryParser;
import com.roladio.banking.exceptions.ClientNotFoundException;
import com.roladio.banking.exceptions.InsufficientFundsException;
import com.roladio.banking.service.ClientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClientController.class)
public class ClientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClientService clientService;
    @MockitoBean
    private TransactionQueryParser queryParser;

    @Test
    void getClientById_whenNotFound_returns404() throws Exception {
        when(clientService.getClientById(99L))
                .thenThrow(new ClientNotFoundException(99L));

        mockMvc.perform(get("/clients/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Client not found: 99"));
    }

    @Test
    void transfer_whenAmountIsNegative_returns400() throws Exception {
        mockMvc.perform(post("/clients/transfer")
                        .contentType("application/json")
                        .content("""
                                {"fromId": 1, "toId": 2, "amount": -100.00}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void transfer_whenFieldsAreMissing_returns400() throws Exception {
        mockMvc.perform(post("/clients/transfer")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fromId").exists())
                .andExpect(jsonPath("$.errors.toId").exists())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void transfer_whenInsufficientFunds_returns409() throws Exception {
        doThrow(new InsufficientFundsException())
                .when(clientService).transfer(any());

        mockMvc.perform(post("/clients/transfer")
                        .contentType("application/json")
                        .content("""
                                {"fromId": 1, "toId": 2, "amount": 100.00}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}
