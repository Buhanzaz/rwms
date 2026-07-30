package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.auth.api.AdminUserResponse;
import dev.buhanzaz.rwms.auth.api.CreateUserRequest;
import dev.buhanzaz.rwms.auth.api.CurrentUserResponse;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RentalAccessIntegrationTest {

    private static final String PASSWORD = "rental-access-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    UserAdministrationService users;

    @Autowired
    MockMvc mvc;

    @ParameterizedTest
    @MethodSource("roleDefaults")
    void defaultsRentalAccessByRoleAndExposesItFromCurrentUser(
            UserGlobalRole role, boolean expectedRentalAccess) {
        AdminUserResponse created = create("rental.default." + role.name().toLowerCase(), role);

        assertThat(created.rentalAccess()).isEqualTo(expectedRentalAccess);
        CurrentUserResponse current = users.currentUser(userAuthentication(created.username()));
        assertThat(current.rentalAccess()).isEqualTo(expectedRentalAccess);
    }

    @Test
    void wmsAdminCanSetRentalAccessButWarehouseManagerCannot() throws Exception {
        AdminUserResponse wmsAdmin = create("rental.wms-admin", UserGlobalRole.WMS_ADMIN);
        AdminUserResponse warehouseManager = create(
                "rental.warehouse-manager", UserGlobalRole.WAREHOUSE_MANAGER);
        AdminUserResponse viewer = create("rental.viewer", UserGlobalRole.VIEWER);

        mvc.perform(put("/api/admin/users/{id}", viewer.id())
                        .with(jwt().jwt(token -> token.subject(wmsAdmin.username()))
                                .authorities(new SimpleGrantedAuthority("ROLE_WMS_ADMIN")))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(updateRequest(viewer, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rentalAccess").value(true));

        AdminUserResponse updated = users.getUser(viewer.id());
        assertThat(updated.rentalAccess()).isTrue();

        mvc.perform(put("/api/admin/users/{id}", viewer.id())
                        .with(jwt().jwt(token -> token.subject(warehouseManager.username()))
                                .authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER")))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(updateRequest(updated, false)))
                .andExpect(status().isForbidden());
    }

    private static Stream<Arguments> roleDefaults() {
        return Stream.of(
                Arguments.of(UserGlobalRole.SYSTEM_ADMIN, true),
                Arguments.of(UserGlobalRole.WMS_ADMIN, true),
                Arguments.of(UserGlobalRole.RENTAL_MANAGER, true),
                Arguments.of(UserGlobalRole.WAREHOUSE_MANAGER, false),
                Arguments.of(UserGlobalRole.VIEWER, false));
    }

    private AdminUserResponse create(String username, UserGlobalRole role) {
        return users.create(
                new CreateUserRequest(
                        username,
                        PASSWORD,
                        null,
                        null,
                        null,
                        null,
                        role,
                        true,
                        List.of()),
                adminAuthentication());
    }

    private String updateRequest(AdminUserResponse user, boolean rentalAccess) {
        return """
                {
                  "expectedVersion":%d,
                  "username":"%s",
                  "active":%s,
                  "globalRole":"%s",
                  "rentalAccess":%s
                }
                """.formatted(
                user.version(), user.username(), user.active(), user.globalRole(), rentalAccess);
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return userAuthentication("admin");
    }

    private UsernamePasswordAuthenticationToken userAuthentication(String username) {
        return UsernamePasswordAuthenticationToken.authenticated(username, "", List.of());
    }
}
