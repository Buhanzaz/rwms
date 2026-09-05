package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.domain.CabinStatusColors;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.CabinStatusColorsRepository;
import dev.buhanzaz.rwms.asset.service.CabinStatusColorsService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP authorization, persisted palette, JPA/Flyway validation and real concurrent-update fencing.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate", "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false", "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CabinStatusColorsIntegrationTest {
  private static final String ENDPOINT = "/api/asset/v1/cabin-settings/status-colors";
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired CabinStatusColorsService service;
  @Autowired CabinStatusColorsRepository repository;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void persistedGlobalPaletteIsVisibleWithoutWarehouseSelectionAndFencesReplacement()
      throws Exception {
    var before = service.get();
    assertThat(before.colors()).hasSize(RentalItemStatus.values().length);
    Map<String, String> colors = new LinkedHashMap<>(before.colors());
    colors.put("FREE", "#aabbcc");
    String body =
        json.writeValueAsString(Map.of("expectedVersion", before.version(), "colors", colors));

    mvc.perform(
            put(ENDPOINT)
                .with(user("WMS_ADMIN", "rwms.read rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(before.version() + 1))
        .andExpect(jsonPath("$.colors.FREE").value("#AABBCC"));
    mvc.perform(get(ENDPOINT).with(user("VIEWER", "rwms.read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.colors.FREE").value("#AABBCC"));
    assertThat(jdbc.queryForObject("select colors->>'FREE' from cabin_status_colors", String.class))
        .isEqualTo("#AABBCC");
    assertThat(repository.count()).isEqualTo(1);
    mvc.perform(
            put(ENDPOINT)
                .with(user("SYSTEM_ADMIN", "rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectsUnauthenticatedUnprivilegedAndIncompleteWrites() throws Exception {
    var palette = service.get();
    String body =
        json.writeValueAsString(
            Map.of("expectedVersion", palette.version(), "colors", palette.colors()));
    mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
    mvc.perform(get(ENDPOINT).with(user("VIEWER", "rwms.write"))).andExpect(status().isForbidden());
    mvc.perform(
            get(ENDPOINT)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject("service")
                                    .claim("principal_type", "SERVICE")
                                    .claim("scope", "rwms.read"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(ENDPOINT)
                .with(user("WAREHOUSE_MANAGER", "rwms.read rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(ENDPOINT)
                .with(user("WMS_ADMIN", "rwms.read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    for (String invalid :
        new String[] {
          json.writeValueAsString(
              Map.of("expectedVersion", palette.version(), "colors", Map.of("FREE", "#000000"))),
          body.replace("\"FREE\"", "\"UNKNOWN\""),
          body.replace(palette.colors().get("FREE"), "red"),
          json.writeValueAsString(Map.of("colors", palette.colors()))
        }) {
      mvc.perform(
              put(ENDPOINT)
                  .with(user("WMS_ADMIN", "rwms.write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalid))
          .andExpect(status().isBadRequest());
    }
    assertThat(service.get()).isEqualTo(palette);
  }

  @Test
  void simultaneousJpaUpdatesCannotOverwriteEachOther() throws Exception {
    CyclicBarrier loaded = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> changeAfterRead(loaded, "#102030"));
      var second = executor.submit(() -> changeAfterRead(loaded, "#405060"));
      int successes = 0;
      int conflicts = 0;
      for (var future : java.util.List.of(first, second)) {
        try {
          future.get(10, TimeUnit.SECONDS);
          successes++;
        } catch (ExecutionException exception) {
          assertThat(exception.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
          conflicts++;
        }
      }
      assertThat(successes).isEqualTo(1);
      assertThat(conflicts).isEqualTo(1);
      assertThat(service.get().colors().get("RENTED")).isIn("#102030", "#405060");
    }
  }

  @Test
  void paletteReadCannotMutateTheManagedMap() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var entity = repository.findById(CabinStatusColors.SINGLETON_ID).orElseThrow();
              assertThatThrownBy(() -> entity.getColors().put("FREE", "#000000"))
                  .isInstanceOf(UnsupportedOperationException.class);
            });
  }

  private void changeAfterRead(CyclicBarrier loaded, String color) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var entity = repository.findById(CabinStatusColors.SINGLETON_ID).orElseThrow();
              Map<String, String> colors = new LinkedHashMap<>(entity.getColors());
              colors.put("RENTED", color);
              try {
                loaded.await(5, TimeUnit.SECONDS);
              } catch (Exception exception) {
                throw new IllegalStateException(exception);
              }
              entity.replace(colors);
              repository.flush();
            });
  }

  private static JwtRequestPostProcessor user(String role, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", role)
                    .claim("scope", scope));
  }
}
