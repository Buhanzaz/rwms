package dev.buhanzaz.rwms.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.dossier.domain.DossierMediaProjection;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaState;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DossierMediaProjectionTest {
  @Test
  void canonicalLifecycleAdvancesAtTheSameMediaGeneration() {
    UUID uploadEvent = UUID.randomUUID();
    DossierMediaProjection projection = projection(uploadEvent);
    UUID readyEvent = UUID.randomUUID();

    assertThat(
            projection.apply(
                projection.getFolderId(), 0, 2, DossierMediaState.READY, readyEvent, now()))
        .isTrue();
    assertThat(projection.getMediaGeneration()).isZero();
    assertThat(projection.getSourceAggregateVersion()).isEqualTo(2);
    assertThat(projection.getState()).isEqualTo(DossierMediaState.READY);
    assertThat(
            projection.apply(
                projection.getFolderId(), 0, 2, DossierMediaState.READY, readyEvent, now()))
        .isFalse();
  }

  @Test
  void failedRotationMayRetainReadyStateWhileSourceVersionAdvances() {
    DossierMediaProjection projection = projection(UUID.randomUUID());
    projection.apply(
        projection.getFolderId(), 0, 2, DossierMediaState.READY, UUID.randomUUID(), now());

    assertThat(
            projection.apply(
                projection.getFolderId(),
                0,
                3,
                DossierMediaState.READY,
                UUID.randomUUID(),
                now()))
        .isTrue();
    assertThat(projection.getState()).isEqualTo(DossierMediaState.READY);
    assertThat(projection.getSourceAggregateVersion()).isEqualTo(3);
  }

  @Test
  void deletionIsAllowedButSameGenerationResurrectionAndIdentityConflictAreRejected() {
    UUID uploadEvent = UUID.randomUUID();
    DossierMediaProjection projection = projection(uploadEvent);

    assertThatThrownBy(
            () ->
                projection.apply(
                    projection.getFolderId(),
                    0,
                    1,
                    DossierMediaState.PROCESSING,
                    UUID.randomUUID(),
                    now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MEDIA_GENERATION_CONFLICT");

    projection.apply(
        projection.getFolderId(), 0, 2, DossierMediaState.READY, UUID.randomUUID(), now());
    projection.apply(
        projection.getFolderId(), 0, 3, DossierMediaState.DELETED, UUID.randomUUID(), now());

    assertThatThrownBy(
            () ->
                projection.apply(
                    projection.getFolderId(),
                    0,
                    4,
                    DossierMediaState.READY,
                    UUID.randomUUID(),
                    now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MEDIA_GENERATION_CONFLICT");
    assertThatThrownBy(
            () ->
                projection.apply(
                    projection.getFolderId(),
                    1,
                    4,
                    DossierMediaState.READY,
                    UUID.randomUUID(),
                    now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MEDIA_GENERATION_CONFLICT");
    assertThat(
            projection.apply(
                projection.getFolderId(),
                0,
                2,
                DossierMediaState.READY,
                UUID.randomUUID(),
                now()))
        .isFalse();
  }

  @Test
  void generationJumpsAreRejected() {
    DossierMediaProjection projection = projection(UUID.randomUUID());

    assertThatThrownBy(
            () ->
                projection.apply(
                    projection.getFolderId(),
                    2,
                    2,
                    DossierMediaState.READY,
                    UUID.randomUUID(),
                    now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MEDIA_GENERATION_CONFLICT");
  }

  @Test
  void logicalFolderCannotChangeAcrossFactsForOneMediaAsset() {
    DossierMediaProjection projection = projection(UUID.randomUUID());

    assertThatThrownBy(
            () ->
                projection.apply(
                    UUID.randomUUID(),
                    0,
                    2,
                    DossierMediaState.READY,
                    UUID.randomUUID(),
                    now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MEDIA_FOLDER_CONFLICT");
  }

  @Test
  void newerSourceVersionCannotMoveMediaGenerationBackwards() {
    DossierMediaProjection projection = projection(UUID.randomUUID());
    projection.apply(
        projection.getFolderId(), 1, 2, DossierMediaState.READY, UUID.randomUUID(), now());

    assertThatThrownBy(
            () ->
                projection.apply(
                    projection.getFolderId(),
                    0,
                    3,
                    DossierMediaState.READY,
                    UUID.randomUUID(),
                    now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MEDIA_GENERATION_CONFLICT");
  }

  @Test
  void olderSourceVersionIsAHarmlessRedeliveryRegardlessOfMediaPayload() {
    DossierMediaProjection projection = projection(UUID.randomUUID());
    UUID readyEvent = UUID.randomUUID();
    projection.apply(
        projection.getFolderId(), 0, 2, DossierMediaState.READY, readyEvent, now());

    assertThat(
            projection.apply(
                projection.getFolderId(),
                1,
                1,
                DossierMediaState.FAILED,
                UUID.randomUUID(),
                now()))
        .isFalse();
    assertThat(projection.getMediaGeneration()).isZero();
    assertThat(projection.getSourceAggregateVersion()).isEqualTo(2);
    assertThat(projection.getState()).isEqualTo(DossierMediaState.READY);
    assertThat(projection.getSourceEventId()).isEqualTo(readyEvent);
  }

  private static DossierMediaProjection projection(UUID sourceEventId) {
    return DossierMediaProjection.project(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        0,
        1,
        DossierMediaState.PROCESSING,
        sourceEventId,
        now());
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now();
  }
}
