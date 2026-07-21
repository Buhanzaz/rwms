package dev.buhanzaz.rwms.dossier.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.dossier.domain.DossierMediaProjection;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaState;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierMediaProjectionMapperTest {
  private final DossierMediaProjectionMapper mapper = new DossierMediaProjectionMapperImpl();

  @Test
  void mapsEverySanitizedMediaReadFieldIncludingFolder() {
    UUID mediaId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    DossierMediaProjection projection =
        DossierMediaProjection.project(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            mediaId,
            folderId,
            findingId,
            3,
            4,
            DossierMediaState.READY,
            UUID.randomUUID(),
            OffsetDateTime.now());

    var response = mapper.toResponse(projection);

    assertThat(response.mediaId()).isEqualTo(mediaId);
    assertThat(response.folderId()).isEqualTo(folderId);
    assertThat(response.findingId()).isEqualTo(findingId);
    assertThat(response.generation()).isEqualTo(3);
    assertThat(response.state()).isEqualTo("READY");
  }
}
