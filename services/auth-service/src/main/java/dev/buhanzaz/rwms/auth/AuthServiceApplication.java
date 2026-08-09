package dev.buhanzaz.rwms.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstraps the RWMS authorization service.
 *
 * <p>The service owns interactive and service identities, OAuth/OIDC client registration,
 * credential verification, roles, and warehouse-access grants.
 */
@SpringBootApplication
public class AuthServiceApplication {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
