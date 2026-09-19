package com.enterprise.funds.transfer.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The transaction used for posting and cancelling. The timeout is the server request deadline (decision D3):
     * Spring applies it as a per-statement query timeout, so a stuck statement ends the request with a rollback.
     */
    @Bean
    public TransactionTemplate postingTransaction(PlatformTransactionManager manager, FundsProperties props) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setTimeout((int) props.requestDeadline().toSeconds());
        return template;
    }
}
