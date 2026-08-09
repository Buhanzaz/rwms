package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.*;

import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImport;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRow;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRowAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportBinding;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportClient;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportJob;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportSource;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemHtmlImportRowRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Advances the media phase of a committed HTML import, including idempotent retry, replacement,
 * skip, and polling recovery.
 *
 * <p>Every private media call occurs between short local transactions. The service delegates only
 * durable COMMITTING recovery to the commit owner; it never makes catalog, plan, or cabin-command
 * decisions itself.
 */
@Service
public class RentalItemHtmlImportMediaService {
  private final RentalItemHtmlImportRepository imports;
  private final RentalItemHtmlImportRowRepository rows;
  private final AssetIdempotencyStore idempotency;
  private final MediaAssetImportClient mediaImports;
  private final RentalItemHtmlImportCommitService commits;
  private final RentalItemHtmlImportProjectionService projections;
  private final RentalItemHtmlImportCodec codec;
  private final TransactionTemplate transactions;

  public RentalItemHtmlImportMediaService(
      RentalItemHtmlImportRepository imports,
      RentalItemHtmlImportRowRepository rows,
      AssetIdempotencyStore idempotency,
      MediaAssetImportClient mediaImports,
      RentalItemHtmlImportCommitService commits,
      RentalItemHtmlImportProjectionService projections,
      RentalItemHtmlImportCodec codec,
      PlatformTransactionManager transactionManager) {
    this.imports = imports;
    this.rows = rows;
    this.idempotency = idempotency;
    this.mediaImports = mediaImports;
    this.commits = commits;
    this.projections = projections;
    this.codec = codec;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  HtmlImportDetailResponse retryMedia(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID id,
      RetryHtmlImportMediaRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import media retry identity is required");
    }
    String operation = "rental-item-html-import.retry-media." + id;
    String requestHash = codec.hash(Map.of("importId", id, "expectedVersion", request.expectedVersion()));
    CommitOperation command =
        new CommitOperation(actorSubjectId, idempotencyKey, operation, requestHash);
    RetryMediaPreparation prepared =
        inTransaction(() -> prepareRetryMedia(id, request.expectedVersion(), command));
    if (prepared.replay() != null) return prepared.replay();
    if (prepared.synchronize()) {
      synchronizeMedia(id);
      return inTransaction(() -> storeMediaCommandResponse(id, command));
    }
    MediaAssetImportJob job =
        mediaImports.retry(
            prepared.mediaJobId(), codec.stableKey("media-retry", id, idempotencyKey.toString()));
    return inTransaction(() -> finishRetryMedia(id, prepared.mediaJobId(), job, command));
  }

  HtmlImportDetailResponse replaceMedia(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID id,
      ReplaceHtmlImportMediaRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import media replacement identity is required");
    }
    String operation = "rental-item-html-import.replace-media." + id;
    String requestHash =
        codec.hash(
            Map.of(
                "importId", id,
                "expectedVersion", request.expectedVersion(),
                "replacements", request.replacements()));
    CommitOperation command =
        new CommitOperation(actorSubjectId, idempotencyKey, operation, requestHash);
    ReplaceMediaPreparation prepared =
        inTransaction(
            () ->
                prepareReplaceMedia(
                    id, request.expectedVersion(), request.replacements(), command));
    if (prepared.replay() != null) return prepared.replay();
    MediaAssetImportJob job =
        mediaImports.replacePreflightSources(
            prepared.mediaJobId(),
            prepared.replacements(),
            codec.stableKey("media-replace", id, idempotencyKey.toString()));
    return inTransaction(() -> finishReplaceMedia(id, prepared.mediaJobId(), job, command));
  }

  HtmlImportDetailResponse skipMedia(
      UUID actorSubjectId,
      UUID idempotencyKey,
      UUID id,
      SkipHtmlImportMediaRequest request) {
    if (actorSubjectId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("HTML import media skip identity is required");
    }
    String operation = "rental-item-html-import.skip-media." + id;
    String requestHash = codec.hash(Map.of("importId", id, "expectedVersion", request.expectedVersion()));
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(actorSubjectId, operation, idempotencyKey, requestHash);
    if (replay.isPresent()) return codec.read(replay.get(), HtmlImportDetailResponse.class);

    RentalItemHtmlImport value = requireImportForUpdate(id);
    RentalItemHtmlImportPlanService.assertVersion(value, request.expectedVersion());
    value.mediaSkipped();
    imports.saveAndFlush(value);
    HtmlImportDetailResponse response = projections.detail(value);
    idempotency.store(actorSubjectId, operation, idempotencyKey, requestHash, 202, response);
    return response;
  }

  void synchronizeMedia(UUID id) {
    if (commits.recoverIfCommitting(id)) return;
    MediaSyncPreparation prepared = inTransaction(() -> prepareMediaSync(id));
    if (prepared.mediaJobId() == null) return;
    MediaAssetImportJob job;
    try {
      job = mediaImports.get(prepared.mediaJobId());
    } catch (AssetNotFoundException ignored) {
      inTransaction(() -> markMediaJobMissing(id, prepared.mediaJobId()));
      return;
    }
    MediaActivationIntent activation = inTransaction(() -> applyMediaObservation(id, job));
    if (activation == null) return;
    try {
      MediaAssetImportJob activated =
          mediaImports.activate(
              activation.mediaJobId(),
              activation.bindings(),
              codec.stableKey("media-activate", activation.importId()));
      inTransaction(() -> applyMediaObservation(id, activated));
    } catch (AssetConflictException ignored) {
      // The CABIN owner proof is delivered asynchronously through Kafka.
      // The next bounded poll repeats the stable activation command.
    }
  }

  private RetryMediaPreparation prepareRetryMedia(
      UUID id, Long expectedVersion, CommitOperation command) {
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash());
    if (replay.isPresent()) {
      return new RetryMediaPreparation(
          codec.read(replay.get(), HtmlImportDetailResponse.class), null, false);
    }
    RentalItemHtmlImport value = requireImportForUpdate(id);
    RentalItemHtmlImportPlanService.assertVersion(value, expectedVersion);
    if (value.getMediaLinkCount() == 0) {
      HtmlImportDetailResponse response = projections.detail(value);
      idempotency.store(
          command.actorSubjectId(),
          command.operation(),
          command.idempotencyKey(),
          command.requestHash(),
          202,
          response);
      return new RetryMediaPreparation(response, null, false);
    }
    if (value.getState() == RentalItemHtmlImportState.ASSETS_COMMITTED
        || value.getState() == RentalItemHtmlImportState.MEDIA_IMPORTING) {
      return new RetryMediaPreparation(null, value.getMediaJobId(), true);
    }
    if (value.getState() == RentalItemHtmlImportState.FAILED && value.getMediaJobId() != null) {
      return new RetryMediaPreparation(null, value.getMediaJobId(), false);
    }
    throw new AssetConflictException("HTML import media cannot be retried in its current state");
  }

  private HtmlImportDetailResponse finishRetryMedia(
      UUID id, UUID expectedJobId, MediaAssetImportJob job, CommitOperation command) {
    HtmlImportDetailResponse replay = replayCommand(command);
    if (replay != null) return replay;
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.FAILED
        || !expectedJobId.equals(value.getMediaJobId())) {
      throw new AssetConflictException("HTML import media retry is no longer current");
    }
    RentalItemHtmlImportCommitService.requireMediaJobIdentity(value, job);
    value.mediaPending();
    applyMediaJob(value, job);
    imports.saveAndFlush(value);
    HtmlImportDetailResponse response = projections.detail(value);
    idempotency.store(
        command.actorSubjectId(),
        command.operation(),
        command.idempotencyKey(),
        command.requestHash(),
        202,
        response);
    return response;
  }

  private ReplaceMediaPreparation prepareReplaceMedia(
      UUID id,
      Long expectedVersion,
      List<HtmlImportMediaReplacement> replacements,
      CommitOperation command) {
    Optional<tools.jackson.databind.JsonNode> replay =
        idempotency.replay(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash());
    if (replay.isPresent()) {
      return new ReplaceMediaPreparation(
          codec.read(replay.get(), HtmlImportDetailResponse.class), null, List.of());
    }
    RentalItemHtmlImport value = requireImportForUpdate(id);
    RentalItemHtmlImportPlanService.assertVersion(value, expectedVersion);
    if (value.getState() != RentalItemHtmlImportState.FAILED || value.getMediaJobId() == null) {
      throw new AssetConflictException(
          "HTML import media links cannot be replaced in its current state");
    }
    return new ReplaceMediaPreparation(
        null, value.getMediaJobId(), mediaReplacementSources(value, replacements));
  }

  private HtmlImportDetailResponse finishReplaceMedia(
      UUID id, UUID expectedJobId, MediaAssetImportJob job, CommitOperation command) {
    HtmlImportDetailResponse replay = replayCommand(command);
    if (replay != null) return replay;
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.FAILED
        || !expectedJobId.equals(value.getMediaJobId())) {
      throw new AssetConflictException("HTML import media replacement is no longer current");
    }
    RentalItemHtmlImportCommitService.requireMediaJobIdentity(value, job);
    value.mediaPending();
    applyMediaJob(value, job);
    imports.saveAndFlush(value);
    HtmlImportDetailResponse response = projections.detail(value);
    idempotency.store(
        command.actorSubjectId(),
        command.operation(),
        command.idempotencyKey(),
        command.requestHash(),
        202,
        response);
    return response;
  }

  private HtmlImportDetailResponse storeMediaCommandResponse(UUID id, CommitOperation command) {
    HtmlImportDetailResponse replay = replayCommand(command);
    if (replay != null) return replay;
    RentalItemHtmlImport value = requireImportForUpdate(id);
    HtmlImportDetailResponse response = projections.detail(value);
    idempotency.store(
        command.actorSubjectId(),
        command.operation(),
        command.idempotencyKey(),
        command.requestHash(),
        202,
        response);
    return response;
  }

  private HtmlImportDetailResponse replayCommand(CommitOperation command) {
    return idempotency
        .replay(
            command.actorSubjectId(),
            command.operation(),
            command.idempotencyKey(),
            command.requestHash())
        .map(node -> codec.read(node, HtmlImportDetailResponse.class))
        .orElse(null);
  }

  private MediaSyncPreparation prepareMediaSync(UUID id) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.ASSETS_COMMITTED
        && value.getState() != RentalItemHtmlImportState.MEDIA_IMPORTING) {
      return new MediaSyncPreparation(null);
    }
    if (value.getMediaJobId() == null) {
      value.fail("MEDIA_JOB_MISSING");
      imports.saveAndFlush(value);
      return new MediaSyncPreparation(null);
    }
    return new MediaSyncPreparation(value.getMediaJobId());
  }

  private MediaActivationIntent applyMediaObservation(UUID id, MediaAssetImportJob job) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if (value.getState() != RentalItemHtmlImportState.ASSETS_COMMITTED
        && value.getState() != RentalItemHtmlImportState.MEDIA_IMPORTING) {
      return null;
    }
    RentalItemHtmlImportCommitService.requireMediaJobIdentity(value, job);
    if (!value.getMediaJobId().equals(job.jobId())) {
      throw RentalItemHtmlImportCommitService.wrongMediaJob();
    }
    MediaActivationIntent activation = applyMediaJob(value, job);
    imports.saveAndFlush(value);
    return activation;
  }

  private Void markMediaJobMissing(UUID id, UUID jobId) {
    RentalItemHtmlImport value = requireImportForUpdate(id);
    if ((value.getState() == RentalItemHtmlImportState.ASSETS_COMMITTED
            || value.getState() == RentalItemHtmlImportState.MEDIA_IMPORTING)
        && jobId.equals(value.getMediaJobId())) {
      value.fail("MEDIA_JOB_NOT_FOUND");
      imports.saveAndFlush(value);
    }
    return null;
  }

  private MediaActivationIntent applyMediaJob(RentalItemHtmlImport value, MediaAssetImportJob job) {
    RentalItemHtmlImportCommitService.requireMediaJobIdentity(value, job);
    if (!value.getMediaJobId().equals(job.jobId())) {
      throw RentalItemHtmlImportCommitService.wrongMediaJob();
    }
    return switch (job.status()) {
      case PREFLIGHT_PENDING, PREFLIGHT_RUNNING -> {
        value.mediaPending();
        yield null;
      }
      case PREFLIGHT_READY -> {
        List<MediaAssetImportBinding> bindings = mediaBindings(value);
        if (bindings.isEmpty()) {
          value.mediaCompleted(false);
          yield null;
        }
        yield new MediaActivationIntent(value.getId(), value.getMediaJobId(), bindings);
      }
      case ACTIVATION_PENDING, ACTIVATION_RUNNING -> {
        value.mediaStarted(job.jobId());
        yield null;
      }
      case COMPLETED -> {
        value.mediaStarted(job.jobId());
        value.mediaCompleted(job.hasWarnings());
        yield null;
      }
      case FAILED -> {
        value.fail(safeMediaFailureCode(job.failureCode()));
        yield null;
      }
    };
  }

  private List<MediaAssetImportBinding> mediaBindings(RentalItemHtmlImport value) {
    return rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId()).stream()
        .filter(row -> row.getAction() != RentalItemHtmlImportRowAction.EXCLUDE)
        .filter(RentalItemHtmlImportRow::hasPhotoLink)
        .map(
            row -> {
              if (row.getTargetRentalItemId() == null) {
                throw new IllegalStateException("Committed HTML media row has no cabin target");
              }
              return new MediaAssetImportBinding(row.getId(), row.getTargetRentalItemId());
            })
        .toList();
  }

  private List<MediaAssetImportSource> mediaReplacementSources(
      RentalItemHtmlImport value, List<HtmlImportMediaReplacement> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      throw new IllegalArgumentException("At least one media link replacement is required");
    }
    Map<UUID, RentalItemHtmlImportRow> rowsById =
        rows.findAllByImportIdOrderBySourcePositionAscIdAsc(value.getId()).stream()
            .collect(Collectors.toMap(RentalItemHtmlImportRow::getId, java.util.function.Function.identity()));
    List<MediaAssetImportSource> result = new ArrayList<>(replacements.size());
    Set<UUID> seen = new HashSet<>();
    for (HtmlImportMediaReplacement replacement : replacements) {
      if (replacement == null || replacement.rowId() == null || !seen.add(replacement.rowId())) {
        throw new IllegalArgumentException("HTML import media replacement rows must be distinct");
      }
      RentalItemHtmlImportRow row = rowsById.get(replacement.rowId());
      if (row == null
          || !row.hasPhotoLink()
          || row.getAction() == RentalItemHtmlImportRowAction.EXCLUDE
          || row.getTargetRentalItemId() == null) {
        throw new AssetConflictException("HTML import media replacement row is not eligible");
      }
      result.add(new MediaAssetImportSource(row.getId(), replacement.publicUrl()));
    }
    return List.copyOf(result);
  }

  private static String safeMediaFailureCode(String value) {
    return value != null && value.matches("^[A-Z][A-Z0-9_]{0,63}$")
        ? value
        : "MEDIA_IMPORT_FAILED";
  }

  private RentalItemHtmlImport requireImportForUpdate(UUID id) {
    return imports
        .findByIdForUpdate(id)
        .orElseThrow(() -> new AssetNotFoundException("HTML import was not found"));
  }

  private <T> T inTransaction(Supplier<T> operation) {
    return transactions.execute(status -> operation.get());
  }

  /**
   * Actor, transport key, operation name, and request digest needed to fence one media command.
   */
  private record CommitOperation(
      UUID actorSubjectId, UUID idempotencyKey, String operation, String requestHash) {}

  /**
   * Transaction result deciding between permanent replay, synchronization of an active job, and a
   * fresh retry of a failed job.
   */
  private record RetryMediaPreparation(
      HtmlImportDetailResponse replay, UUID mediaJobId, boolean synchronize) {}

  /**
   * Transaction result carrying either a permanent replay or the replacement sources that may be
   * submitted after commit.
   */
  private record ReplaceMediaPreparation(
      HtmlImportDetailResponse replay,
      UUID mediaJobId,
      List<MediaAssetImportSource> replacements) {}

  /**
   * Identifies the active media job whose external state must be reconciled after the local read.
   */
  private record MediaSyncPreparation(UUID mediaJobId) {}

  /**
   * Post-commit media activation payload; keeping it separate prevents remote work inside the local
   * asset transaction.
   */
  private record MediaActivationIntent(
      UUID importId, UUID mediaJobId, List<MediaAssetImportBinding> bindings) {}
}
