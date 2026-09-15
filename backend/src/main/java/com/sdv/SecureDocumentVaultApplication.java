package com.sdv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// M08 MVP OAuth (docs/spec/SDV_M08_TOKEN_CONTRACT.md): @ConfigurationPropertiesScan registers
// SecretProperties(F-BE-128, an immutable record) as a bean via Spring Boot's configuration-
// properties binding - @Component on a @ConfigurationProperties record instead conflicts with
// normal constructor-autowiring (each record field gets misread as a bean dependency).
//
// M09A (F-BE-074): @EnableScheduling registers Spring's @Scheduled bean post-processor. This
// alone starts no background work - com.sdv.event.infrastructure.OutboxEventPublisher's
// @Scheduled method only exists as a bean when sdv.sync.outbox-publisher.enabled=true
// (@ConditionalOnProperty, default false in every profile - see application.yml), so simply
// enabling scheduling infrastructure here has no runtime effect until that flag is set.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SecureDocumentVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureDocumentVaultApplication.class, args);
    }

}
