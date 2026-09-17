package com.roladio.banking.config;

import com.roladio.banking.ai.BankingTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider bankingToolCallbackProvider(BankingTools bankingTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(bankingTools)
                .build();
    }
}