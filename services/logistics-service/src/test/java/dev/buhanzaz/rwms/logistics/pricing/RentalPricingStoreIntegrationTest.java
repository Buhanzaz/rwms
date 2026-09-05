package dev.buhanzaz.rwms.logistics.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingSnapshot;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.rental-inquiry.outbox-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RentalPricingStoreIntegrationTest {
  private static final UUID TYPE = UUID.randomUUID();
  private static final UUID CATEGORY = UUID.randomUUID();
  private static final UUID OTHER_CATEGORY = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final String SINGLETON = "00000000-0000-0000-0000-000000000001";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired RentalPricingStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetPrices() {
    jdbc.update("delete from rental_pricing_rate");
    assertThat(
            jdbc.update(
                "update rental_pricing_settings set version=0,"
                    + " updated_by_subject_id=null, updated_at=current_timestamp"))
        .isEqualTo(1);
  }

  @Test
  void migrationSeedsReadOnlyZeroRevisionAndJpaValidatesTheCompleteSchema() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version='98' and success",
                Long.class))
        .isEqualTo(1);
    var first = store.read();
    var second = store.read();
    assertThat(first).isEqualTo(second);
    assertThat(first.version()).isZero();
    assertThat(first.rates()).isEmpty();
    assertThat(first.updatedBySubjectId()).isNull();
    assertThat(first.updatedAt()).isNotNull();
    assertThatThrownBy(() -> first.rates().add(new RentalPricingSnapshot.Rate(TYPE, CATEGORY, 1)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void persistsExactRatesAndReplacesOnlyTheAddressedPair() {
    var first = store.update(0, TYPE, CATEGORY, 8000, ACTOR);
    assertThat(first.version()).isEqualTo(1);
    assertThat(first.updatedBySubjectId()).isEqualTo(ACTOR);
    store.update(1, TYPE, OTHER_CATEGORY, 10000, ACTOR);
    var updated = store.update(2, TYPE, CATEGORY, Long.MAX_VALUE, ACTOR);
    assertThat(updated.version()).isEqualTo(3);
    assertThat(store.read().rates())
        .containsExactlyInAnyOrder(
            new RentalPricingSnapshot.Rate(TYPE, CATEGORY, Long.MAX_VALUE),
            new RentalPricingSnapshot.Rate(TYPE, OTHER_CATEGORY, 10000));
    var reset = store.update(3, TYPE, CATEGORY, 0, ACTOR);
    assertThat(reset.version()).isEqualTo(4);
    assertThat(store.read().rates())
        .containsExactly(new RentalPricingSnapshot.Rate(TYPE, OTHER_CATEGORY, 10000));
    assertThat(jdbc.queryForObject("select count(*) from rental_pricing_rate", Long.class))
        .isEqualTo(1);
  }

  @Test
  void unchangedValueKeepsVersionButAStaleWriteStillConflicts() {
    assertThat(store.update(0, TYPE, CATEGORY, 0, ACTOR).version()).isZero();
    var saved = store.update(0, TYPE, CATEGORY, 8000, ACTOR);
    assertThat(store.update(1, TYPE, CATEGORY, 8000, UUID.randomUUID())).isEqualTo(saved);
    assertThatThrownBy(() -> store.update(0, TYPE, CATEGORY, 8000, ACTOR))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.code()).isEqualTo("RENTAL_PRICING_VERSION_CONFLICT");
            });
    assertThat(store.read()).isEqualTo(saved);
  }

  @Test
  void concurrentEditsToDifferentPairsShareTheSameVersionFence() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> updateConcurrently(CATEGORY, ready, start));
      var second = executor.submit(() -> updateConcurrently(OTHER_CATEGORY, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("saved", "RENTAL_PRICING_VERSION_CONFLICT");
    } finally {
      start.countDown();
    }
    assertThat(store.read().version()).isEqualTo(1);
    assertThat(store.read().rates()).hasSize(1);
  }

  @Test
  void invalidWriteRollsBackAndDatabaseRejectsInvalidStoredRates() {
    assertThatThrownBy(() -> store.update(0, TYPE, CATEGORY, -1, ACTOR))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.read().version()).isZero();
    assertThat(store.read().rates()).isEmpty();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into rental_pricing_rate values (?::uuid, ?::uuid, ?::uuid, 0)",
                    SINGLETON,
                    TYPE.toString(),
                    CATEGORY.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
    store.update(0, TYPE, CATEGORY, 8000, ACTOR);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into rental_pricing_rate values (?::uuid, ?::uuid, ?::uuid, 9000)",
                    SINGLETON,
                    TYPE.toString(),
                    CATEGORY.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(store.read().rates())
        .containsExactly(new RentalPricingSnapshot.Rate(TYPE, CATEGORY, 8000));
  }

  @Test
  void missingSettingsFailExplicitlyWithoutRecreatingOrInventingZeroPrices() {
    jdbc.update("delete from rental_pricing_settings");
    try {
      assertThatThrownBy(store::read)
          .isInstanceOfSatisfying(
              OrderProblemException.class,
              error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.code()).isEqualTo("RENTAL_PRICING_UNAVAILABLE");
              });
      assertThatThrownBy(() -> store.update(0, TYPE, CATEGORY, 8000, ACTOR))
          .isInstanceOf(OrderProblemException.class);
      assertThat(jdbc.queryForObject("select count(*) from rental_pricing_settings", Long.class))
          .isZero();
    } finally {
      jdbc.update(
          "insert into rental_pricing_settings (id, version, updated_at) values (?::uuid, 0,"
              + " current_timestamp)",
          SINGLETON);
    }
  }

  private String updateConcurrently(UUID categoryId, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS))
      throw new AssertionError("Concurrent test did not start");
    try {
      store.update(0, TYPE, categoryId, 8000, ACTOR);
      return "saved";
    } catch (OrderProblemException error) {
      return error.code();
    }
  }
}
