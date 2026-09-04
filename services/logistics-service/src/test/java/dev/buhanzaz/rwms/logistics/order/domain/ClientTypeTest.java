package dev.buhanzaz.rwms.logistics.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers normalized sole-proprietor contact invariants without inferring them from names. */
class ClientTypeTest {
  @Test
  void soleProprietorRequiresAContactPersonLikeOtherBusinessClients() {
    assertThat(ClientType.SOLE_PROPRIETOR.requiresContactPerson()).isTrue();
    assertThat(ClientType.LEGAL_ENTITY.requiresContactPerson()).isTrue();
    assertThat(ClientType.INDIVIDUAL.requiresContactPerson()).isFalse();

    assertThatThrownBy(
            () ->
                OrderClient.create(
                    ClientType.SOLE_PROPRIETOR,
                    "ИП Петров",
                    "ип петров",
                    "+79990000001",
                    "+79990000001",
                    null,
                    null,
                    null,
                    UUID.randomUUID(),
                    "Менеджер",
                    null,
                    null,
                    List.of(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "a".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contactPerson");
  }
}
