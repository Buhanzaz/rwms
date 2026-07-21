package dev.buhanzaz.rwms.dossier.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DossierApiModels {
  private DossierApiModels() {}

  public record CabinDossierResponse(
      UUID cabinId, List<Activity> activities, String nextCursor, Visibility visibility) {}

  public record Activity(
      UUID activityId,
      UUID cabinId,
      UUID warehouseId,
      String activityCode,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      ActorReference actorRef,
      SourceReference sourceRef,
      List<MediaProjection> media) {}

  public record ActorReference(UUID subjectId, String principalType, String profileRevision) {}

  public record SourceReference(
      String producer,
      String aggregateType,
      UUID aggregateId,
      @JsonInclude(JsonInclude.Include.NON_NULL) UUID secondaryId) {}

  public record MediaProjection(
      UUID mediaId, UUID folderId, UUID findingId, long generation, String state) {}

  public enum Visibility { COMPLETE, PARTIAL }
}
