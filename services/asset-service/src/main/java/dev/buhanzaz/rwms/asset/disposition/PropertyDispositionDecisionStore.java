package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionEffect;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionFence;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionPreparedContent;

import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionFenceState;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns one durable property-disposition decision record: its fence, frozen content plan,
 * permanent effect replay, and append-only audit facts.
 *
 * <p>Callers retain all lifecycle and authorization decisions. This store only serializes and
 * locks persisted facts already chosen by those callers.
 */
@Service
final class PropertyDispositionDecisionStore {
  private final PropertyDispositionFenceRepository fences;
  private final PropertyDispositionFenceContentRepository contents;
  private final PropertyDispositionEffectRepository effects;
  private final PropertyDispositionCodec codec;
  private final JdbcTemplate jdbc;

  PropertyDispositionDecisionStore(
      PropertyDispositionFenceRepository fences,
      PropertyDispositionFenceContentRepository contents,
      PropertyDispositionEffectRepository effects,
      PropertyDispositionCodec codec,
      JdbcTemplate jdbc) {
    this.fences = fences;
    this.contents = contents;
    this.effects = effects;
    this.codec = codec;
    this.jdbc = jdbc;
  }

  /** Serializes one decision's prepare/apply replay state inside the caller's local transaction. */
  void lockDecision(UUID decisionId) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        "asset-property-disposition:" + decisionId);
  }

  Optional<PropertyDispositionFence> findForDecisionUpdate(UUID decisionId) {
    return fences.findByDecisionIdForUpdate(decisionId);
  }

  void assertNoPreparedFence(PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    if (fences
        .findByPhysicalAssetForUpdate(
            assetKind, assetId, warehouseId, PropertyDispositionFenceState.PREPARED)
        .isPresent()) {
      throw new dev.buhanzaz.rwms.asset.service.AssetConflictException(
          "Property asset already has a prepared disposition fence");
    }
  }

  boolean hasPreparedFence(PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from property_disposition_fence
          where asset_kind=? and asset_id=? and warehouse_id=? and state='PREPARED'
        )
        """,
        Boolean.class,
        assetKind.name(),
        assetId,
        warehouseId);
    return Boolean.TRUE.equals(active);
  }

  void savePreparedFence(PropertyDispositionFence fence) {
    fences.saveAndFlush(fence);
  }

  void savePreparedContents(List<PropertyDispositionFenceContent> lines) {
    contents.saveAllAndFlush(lines);
  }

  List<PropertyDispositionFenceContent> preparedContents(UUID decisionId) {
    return contents.findAllByDecisionIdOrderByEquipmentIdAsc(decisionId);
  }

  MaintenancePropertyDispositionFence fenceResponse(PropertyDispositionFence fence) {
    List<MaintenancePropertyDispositionPreparedContent> lines = preparedContents(fence.getDecisionId())
        .stream()
        .map(
            line ->
                new MaintenancePropertyDispositionPreparedContent(
                    line.getEquipmentId(),
                    line.getSourceBalanceId(),
                    line.getExpectedBalanceVersion(),
                    line.getCurrentQuantity(),
                    line.getMoveQuantity(),
                    line.getDispositionQuantity()))
        .toList();
    return new MaintenancePropertyDispositionFence(
        fence.getDecisionId(),
        fence.getState(),
        fence.getRequestSha256(),
        fence.getWarehouseId(),
        fence.getAssetKind(),
        fence.getAssetId(),
        fence.getDisposition(),
        fence.getMaintenanceCustodyClaimId(),
        fence.getMaintenanceCustodyVersion(),
        lines,
        fence.getPreparedAt(),
        fence.getAppliedAt());
  }

  MaintenancePropertyDispositionEffect effectResponse(PropertyDispositionFence fence) {
    PropertyDispositionEffect effect = effects.findByDecisionId(fence.getDecisionId())
        .orElseThrow(() -> new IllegalStateException("Applied property disposition lacks its immutable effect"));
    if (!Objects.equals(effect.getEffectId(), fence.getEffectId())
        || !effect
            .getResponseSha256()
            .equals(AssetChecksum.sha256(effect.getResponseBody().getBytes(StandardCharsets.UTF_8)))) {
      throw new IllegalStateException("Stored property disposition effect is corrupt");
    }
    return codec.effect(effect.getResponseBody());
  }

  void completeEffect(
      PropertyDispositionFence fence,
      UUID completedMovementTaskId,
      MaintenancePropertyDispositionEffect response) {
    String responseBody = codec.canonicalJson(response);
    effects.saveAndFlush(
        PropertyDispositionEffect.record(
            response.effectId(),
            response.decisionId(),
            responseBody,
            AssetChecksum.sha256(responseBody.getBytes(StandardCharsets.UTF_8)),
            response.appliedAt()));
    fence.apply(response.effectId(), completedMovementTaskId, response.appliedAt());
    fences.saveAndFlush(fence);
  }

  void writeAudit(
      UUID decisionId,
      String eventType,
      UUID actorSubjectId,
      String requestSha256,
      Map<String, ?> eventBody) {
    String body = codec.canonicalJson(eventBody);
    jdbc.update(
        """
        insert into property_disposition_audit_event(
          event_id,decision_id,event_type,actor_subject_id,request_sha256,event_body,event_sha256,occurred_at)
        values (?,?,?,?,?,?::jsonb,?,clock_timestamp())
        """,
        UUID.randomUUID(),
        decisionId,
        eventType,
        actorSubjectId,
        requestSha256,
        body,
        AssetChecksum.sha256(body.getBytes(StandardCharsets.UTF_8)));
  }
}
