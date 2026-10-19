package dev.buhanzaz.rwms.logistics.photo;

import static dev.buhanzaz.rwms.logistics.photo.CabinPhotoPresentationApiModels.*;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.photo.domain.CabinPhotoPresentation;
import dev.buhanzaz.rwms.logistics.pricing.api.CabinRentalPricesResponse;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingService;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates creation and anonymous resolution of immutable cabin photo presentations. It
 * verifies asset identity/version and media ownership through private service credentials before
 * delegating the only database mutation to the local transactional store.
 */
@Service
@RequiredArgsConstructor
public class CabinPhotoPresentationService {
  private static final Set<String> SUPPORTED_VARIANTS = Set.of("SMALL", "LARGE");
  private static final int MAXIMUM_PHOTOS = 100;
  private static final String PUBLIC_API_PREFIX =
      "/api/logistics/public/v1/cabin-photo-presentations/";

  private final CabinPhotoPresentationStore store;
  private final CabinPhotoPresentationTokenService tokens;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsAuthorizer access;
  private final ObjectMapper json;
  private final RentalPricingService pricing;

  /**
   * Creates one immutable photo/display-metadata snapshot or returns the exact creator-scoped
   * idempotent replay. External reads run without a local transaction; a concurrent unique conflict
   * is resolved only after the failed independent insert transaction has rolled back.
   */
  public CreationResult create(
      Jwt jwt,
      UUID cabinId,
      UUID idempotencyKey,
      CreateCabinPhotoPresentationRequest request) {
    if (cabinId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Cabin photo presentation request is invalid");
    }
    access.requireEdit(jwt, request.warehouseId());
    UUID subjectId = access.subjectId(jwt);
    String requestSha256 = requestHash(cabinId, request);

    CabinPhotoPresentation replay = store.findReplay(subjectId, idempotencyKey).orElse(null);
    if (replay != null) {
      return replay(replay, requestSha256);
    }

    LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot cabin = readCabin(cabinId);
    validateCabin(cabinId, request, cabin);
    CabinRentalPricesResponse price = pricing.prices(request.warehouseId(), List.of(cabinId));
    if (price.cabins().getFirst().rentalItemVersion() != cabin.version()) {
      throw conflict(
          "CABIN_VERSION_CONFLICT", "Бытовка изменилась при фиксации цены; обновите карточку");
    }
    CabinPhotoPresentationMetadataSnapshot metadata = metadata(cabin, price);
    List<CabinPhotoPresentationPhotoSnapshot> photos =
        readPhotos(request.warehouseId(), cabinId);
    CabinPhotoPresentation candidate =
        CabinPhotoPresentation.create(
            cabinId,
            cabin.number(),
            request.warehouseId(),
            request.expectedRentalItemVersion(),
            subjectId,
            idempotencyKey,
            requestSha256,
            writePhotos(photos),
            writeMetadata(metadata),
            now());
    try {
      return new CreationResult(response(store.insert(candidate)), false);
    } catch (DataIntegrityViolationException exception) {
      CabinPhotoPresentation winner = store.findReplay(subjectId, idempotencyKey).orElse(null);
      if (winner == null) throw exception;
      return replay(winner, requestSha256);
    }
  }

  /** Resolves only presentation-safe public metadata for a valid domain-specific token. */
  public PublicCabinPhotoPresentationResponse publicPresentation(String token) {
    CabinPhotoPresentation presentation = resolve(token);
    List<CabinPhotoPresentationPhotoSnapshot> snapshots = readPhotos(presentation);
    List<CabinPhotoPresentationPhotoResponse> photos =
        snapshots.stream().map(photo -> publicPhoto(token, photo)).toList();
    CabinPhotoPresentationMetadataSnapshot metadata = readMetadata(presentation);
    return new PublicCabinPhotoPresentationResponse(
        presentation.getId(),
        presentation.getCabinNumber(),
        metadata.pricingVersion(),
        metadata.monthlyPriceRubles(),
        metadata.dimensions(),
        metadata.finishing(),
        metadata.category(),
        metadata.characteristics(),
        metadata.linoleum(),
        presentation.getCreatedAt(),
        photos);
  }

  /**
   * Reads one exact SMALL/LARGE generation only after proving it belongs to the immutable snapshot.
   * All token, owner, generation and variant mismatches intentionally collapse to not-found.
   */
  public LogisticsDependencyGateway.MediaContent media(
      String token, UUID mediaId, long generation, String variant) {
    CabinPhotoPresentation presentation = resolve(token);
    if (!SUPPORTED_VARIANTS.contains(variant)) throw photoNotFound();
    boolean found =
        readPhotos(presentation).stream()
            .anyMatch(
                photo ->
                    photo.mediaId().equals(mediaId)
                        && photo.generation() == generation
                        && ("SMALL".equals(variant) || photo.contentVariant().equals(variant)));
    if (!found) throw photoNotFound();
    try {
      LogisticsDependencyGateway.MediaContent content =
          dependencies.readCabinPresentationMedia(
              presentation.getWarehouseId(),
              presentation.getCabinId(),
              mediaId,
              generation,
              variant);
      if (content == null
          || content.bytes() == null
          || content.contentType() == null
          || !"image/webp".equalsIgnoreCase(content.contentType().split(";", 2)[0].trim())) {
        throw dependencyMismatch("Media-service returned invalid presentation image content");
      }
      return content;
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw photoNotFound();
      }
      throw exception;
    }
  }

  private CreationResult replay(CabinPhotoPresentation presentation, String requestSha256) {
    if (!presentation.matchesRequest(requestSha256)) {
      throw conflict(
          "IDEMPOTENCY_KEY_REUSED",
          "Idempotency-Key уже использован для другого фото-представления");
    }
    return new CreationResult(response(presentation), true);
  }

  private LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot readCabin(UUID cabinId) {
    try {
      return dependencies.readCabinPhotoPresentationSnapshot(cabinId);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw new OrderProblemException(
            HttpStatus.NOT_FOUND, "CABIN_NOT_FOUND", "Бытовка не найдена");
      }
      throw exception;
    }
  }

  private static void validateCabin(
      UUID cabinId,
      CreateCabinPhotoPresentationRequest request,
      LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot cabin) {
    if (cabin == null || !cabinId.equals(cabin.assetId())) {
      throw dependencyMismatch("Asset-service returned a mismatched cabin identity");
    }
    if (!request.warehouseId().equals(cabin.warehouseId())) {
      throw conflict(
          "CABIN_WAREHOUSE_MISMATCH", "Бытовка не принадлежит выбранному складу");
    }
    if (request.expectedRentalItemVersion() != cabin.version()) {
      throw conflict(
          "CABIN_VERSION_CONFLICT", "Паспорт бытовки изменился; обновите карточку и повторите");
    }
    if (cabin.number() == null || cabin.number().isBlank() || cabin.number().trim().length() > 128) {
      throw conflict("CABIN_NUMBER_MISSING", "У бытовки отсутствует корректный номер");
    }
    if (cabin.characteristics() == null) {
      throw dependencyMismatch("Asset-service returned missing photo-presentation metadata");
    }
  }

  private static CabinPhotoPresentationMetadataSnapshot metadata(
      LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot cabin,
      CabinRentalPricesResponse price) {
    try {
      return new CabinPhotoPresentationMetadataSnapshot(
          cabin.dimensions(),
          cabin.finishing(),
          cabin.category(),
          cabin.characteristics(),
          cabin.linoleum(),
          price.pricingVersion(),
          price.cabins().getFirst().monthlyPriceRubles());
    } catch (IllegalArgumentException exception) {
      throw dependencyMismatch("Asset-service returned invalid photo-presentation metadata");
    }
  }

  private List<CabinPhotoPresentationPhotoSnapshot> readPhotos(UUID warehouseId, UUID cabinId) {
    List<LogisticsDependencyGateway.CabinMediaSnapshot> snapshots =
        dependencies.readCabinMediaSnapshots(warehouseId, List.of(cabinId));
    if (snapshots == null
        || snapshots.size() != 1
        || snapshots.getFirst() == null
        || !cabinId.equals(snapshots.getFirst().cabinId())
        || snapshots.getFirst().photos() == null) {
      throw dependencyMismatch("Media-service returned a mismatched cabin photo snapshot");
    }
    LogisticsDependencyGateway.CabinMediaSnapshot snapshot = snapshots.getFirst();
    List<LogisticsDependencyGateway.CabinMediaPhoto> source = snapshot.photos();
    if (snapshot.photoCount() < 0) {
      throw dependencyMismatch("Media-service returned an invalid cabin photo count");
    }
    if (snapshot.photoCount() > MAXIMUM_PHOTOS || source.size() > MAXIMUM_PHOTOS) {
      throw conflict(
          "CABIN_PHOTO_LIMIT_EXCEEDED",
          "Для одного фото-представления доступно не более 100 фотографий");
    }
    if (snapshot.photoCount() != source.size()) {
      throw conflict(
          "CABIN_PHOTO_PROCESSING_INCOMPLETE",
          "Не все фотографии готовы для представления; повторите позже");
    }
    Set<String> identities = new HashSet<>();
    List<CabinPhotoPresentationPhotoSnapshot> photos = new ArrayList<>(source.size());
    for (LogisticsDependencyGateway.CabinMediaPhoto photo : source) {
      if (photo == null
          || photo.mediaId() == null
          || photo.generation() < 1
          || photo.sortOrder() < 0
          || photo.availableVariants() == null) {
        throw dependencyMismatch("Media-service returned malformed cabin photo metadata");
      }
      Set<String> variants = new HashSet<>(photo.availableVariants());
      if (variants.size() != photo.availableVariants().size()
          || !SUPPORTED_VARIANTS.containsAll(variants)) {
        throw dependencyMismatch("Media-service returned an unsupported cabin photo variant");
      }
      if (!variants.contains("SMALL")) {
        throw conflict(
            "CABIN_PHOTO_VARIANTS_INCOMPLETE",
            "Не все фотографии имеют готовую миниатюру; повторите позже");
      }
      String identity = photo.mediaId() + ":" + photo.generation();
      if (!identities.add(identity)) {
        throw dependencyMismatch("Media-service returned duplicate cabin photo metadata");
      }
      photos.add(
          new CabinPhotoPresentationPhotoSnapshot(
              photo.mediaId(),
              photo.generation(),
              photo.sortOrder(),
              variants.contains("LARGE") ? "LARGE" : "SMALL"));
    }
    photos.sort(
        Comparator.comparingInt(CabinPhotoPresentationPhotoSnapshot::sortOrder)
            .thenComparing(photo -> photo.mediaId().toString()));
    for (int index = 0; index < photos.size(); index++) {
      if (photos.get(index).sortOrder() != index) {
        throw dependencyMismatch(
            "Media-service returned non-contiguous cabin photo presentation positions");
      }
    }
    if (photos.isEmpty()) {
      throw conflict(
          "CABIN_PHOTOS_EMPTY", "У бытовки нет готовых фотографий для представления");
    }
    return List.copyOf(photos);
  }

  private CabinPhotoPresentation resolve(String token) {
    UUID presentationId;
    try {
      presentationId = tokens.verify(token);
    } catch (
        CabinPhotoPresentationTokenService.InvalidCabinPhotoPresentationTokenException exception) {
      throw presentationNotFound();
    }
    return store
        .findById(presentationId)
        .orElseThrow(CabinPhotoPresentationService::presentationNotFound);
  }

  private CabinPhotoPresentationResponse response(CabinPhotoPresentation presentation) {
    String token = tokens.issue(presentation.getId());
    return new CabinPhotoPresentationResponse(
        presentation.getId(),
        presentation.getVersion(),
        presentation.getCabinId(),
        presentation.getCabinNumber(),
        readPhotos(presentation).size(),
        presentation.getCreatedAt(),
        "/photos/" + token);
  }

  private CabinPhotoPresentationPhotoResponse publicPhoto(
      String token, CabinPhotoPresentationPhotoSnapshot photo) {
    String base =
        PUBLIC_API_PREFIX
            + token
            + "/media/"
            + photo.mediaId()
            + "/"
            + photo.generation()
            + "/";
    return new CabinPhotoPresentationPhotoResponse(
        photo.mediaId(),
        photo.generation(),
        photo.sortOrder(),
        base + "SMALL",
        base + photo.contentVariant());
  }

  private String writePhotos(List<CabinPhotoPresentationPhotoSnapshot> photos) {
    try {
      return json.writeValueAsString(photos);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Photo presentation snapshot cannot be serialized", exception);
    }
  }

  private String writeMetadata(CabinPhotoPresentationMetadataSnapshot metadata) {
    try {
      return json.writeValueAsString(metadata);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Photo presentation metadata cannot be serialized", exception);
    }
  }

  private List<CabinPhotoPresentationPhotoSnapshot> readPhotos(
      CabinPhotoPresentation presentation) {
    try {
      return json.readValue(
          presentation.getPhotoSnapshotJson(),
          new TypeReference<List<CabinPhotoPresentationPhotoSnapshot>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored photo presentation snapshot is corrupt", exception);
    }
  }

  private CabinPhotoPresentationMetadataSnapshot readMetadata(
      CabinPhotoPresentation presentation) {
    try {
      return json.readValue(
          presentation.getMetadataSnapshotJson(),
          CabinPhotoPresentationMetadataSnapshot.class);
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored photo presentation metadata is corrupt", exception);
    }
  }

  private static String requestHash(UUID cabinId, CreateCabinPhotoPresentationRequest request) {
    String canonical =
        "CREATE_CABIN_PHOTO_PRESENTATION\n"
            + cabinId
            + "\n"
            + request.warehouseId()
            + "\n"
            + request.expectedRentalItemVersion();
    return LogisticsEventStore.sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static LogisticsDependencyException dependencyMismatch(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

  private static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  private static OrderProblemException presentationNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND,
        "CABIN_PHOTO_PRESENTATION_NOT_FOUND",
        "Фото-представление не найдено");
  }

  private static OrderProblemException photoNotFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND,
        "CABIN_PHOTO_NOT_FOUND",
        "Фотография не найдена");
  }

  /** One creation result plus the exact replay marker needed by the HTTP response contract. */
  public record CreationResult(CabinPhotoPresentationResponse response, boolean replayed) {}
}
