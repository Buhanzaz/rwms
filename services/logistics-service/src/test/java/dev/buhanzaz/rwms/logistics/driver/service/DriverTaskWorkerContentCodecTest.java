package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Verifies replay-safe persistence of structured driver content and compatibility of old rows. */
class DriverTaskWorkerContentCodecTest {
  private final DriverTaskWorkerContentCodec codec =
      new DriverTaskWorkerContentCodec(new ObjectMapper());

  @Test
  void roundTripsStructuredContentAndReadsPreMigrationEmptyObject() {
    DriverTaskWorkerContent content =
        new DriverTaskWorkerContent(
            "Источник → Назначение",
            List.of(
                new DriverTaskWorkerContent.Work(
                    UUID.randomUUID(), "Загрузить №172", 1, "шт.", 15, "Проверить груз")),
            List.of(
                new DriverTaskWorkerContent.Material(
                    UUID.randomUUID(), "Бытовка №172", 1, "шт.")),
            List.of(
                new DriverTaskWorkerContent.Comment(
                    UUID.randomUUID(),
                    "Комментарий",
                    "Логист",
                    OffsetDateTime.of(2026, 9, 14, 8, 0, 0, 0, ZoneOffset.UTC))));

    assertThat(codec.decode(codec.encode(content))).isEqualTo(content);
    assertThat(codec.decode("{}")).isEqualTo(DriverTaskWorkerContent.empty());
  }
}
