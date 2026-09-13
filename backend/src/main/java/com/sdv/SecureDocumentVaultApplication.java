package com.sdv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// M08 MVP OAuth (docs/spec/SDV_M08_TOKEN_CONTRACT.md): @ConfigurationPropertiesScan registers
// SecretProperties(F-BE-128, an immutable record) as a bean via Spring Boot's configuration-
// properties binding - @Component on a @ConfigurationProperties record instead conflicts with
// normal constructor-autowiring (each record field gets misread as a bean dependency).
@SpringBootApplication
@ConfigurationPropertiesScan
public class SecureDocumentVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureDocumentVaultApplication.class, args);
    }

}
