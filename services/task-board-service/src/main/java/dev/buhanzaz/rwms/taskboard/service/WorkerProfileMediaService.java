package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerProfileAvatarScope;
import dev.buhanzaz.rwms.taskboard.config.WorkerProfileMediaProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Establishes the media owner proof for the authenticated worker's server-owned avatar. */
@Service
public class WorkerProfileMediaService {
  static final String OWNER_TYPE = "TASK_BOARD_WORKER_PROFILE";
  static final String CONTEXT = "PROFILE_AVATAR";
  static final String REGISTRATION = "worker-profile-media";
  static final String SCOPE = "media.task-board";

  private final WorkforceService workforce;
  private final RestClient media;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String mediaBaseUrl;

  public WorkerProfileMediaService(
      WorkforceService workforce,
      @Qualifier("workerProfileMediaRestClient") RestClient media,
      OAuth2AuthorizedClientManager authorizedClients,
      WorkerProfileMediaProperties properties) {
    this.workforce = workforce;
    this.media = media;
    this.authorizedClients = authorizedClients;
    this.mediaBaseUrl = normalizeBaseUrl(properties.baseUrl());
  }

  /** Returns an upload/read scope only for the active authenticated worker profile. */
  public WorkerProfileAvatarScope prepare(UUID workerId, UUID warehouseId) {
    var worker = workforce.requireWorker(warehouseId, workerId);
    if (!worker.isActive()) {
      throw new NotFoundException("Рабочий не найден");
    }
    UUID proofEventId =
        UUID.nameUUIDFromBytes(
            ("worker-profile-avatar-proof:" + workerId).getBytes(StandardCharsets.UTF_8));
    OwnerProofResponse response;
    try {
      response =
          media
              .post()
              .uri(mediaBaseUrl + "/api/internal/media/v1/owner-proofs")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              .body(
                  new OwnerProofRequest(
                      OWNER_TYPE, workerId, warehouseId, 0, 0, proofEventId, true))
              .retrieve()
              .body(OwnerProofResponse.class);
    } catch (RestClientResponseException exception) {
      throw new ExternalServiceException(
          "Media-service rejected the worker profile avatar scope", exception);
    } catch (RuntimeException exception) {
      throw new ExternalServiceException(
          "Worker profile avatar dependency is unavailable", exception);
    }
    if (response == null
        || !OWNER_TYPE.equals(response.ownerType())
        || !workerId.equals(response.ownerId())
        || !warehouseId.equals(response.warehouseId())
        || response.ownerRevision() == null
        || response.ownerRevision() != 0
        || response.aggregateVersion() == null
        || response.aggregateVersion() != 0
        || !proofEventId.equals(response.proofEventId())
        || !Boolean.TRUE.equals(response.active())) {
      throw new ExternalServiceException(
          "Media-service returned a mismatched worker profile avatar scope", null);
    }
    return new WorkerProfileAvatarScope(OWNER_TYPE, workerId, warehouseId, CONTEXT);
  }

  private String bearer() {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
            .principal("task-board-service:" + REGISTRATION)
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(SCOPE))) {
      throw new ExternalServiceException(
          "Worker profile media token does not have its exact approved scope", null);
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private static String normalizeBaseUrl(URI value) {
    if (value == null
        || value.getScheme() == null
        || value.getHost() == null
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null) {
      throw new IllegalArgumentException("Media-service base URL must be an absolute origin URI");
    }
    String result = value.toString();
    return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
  }

  private record OwnerProofRequest(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {}

  private record OwnerProofResponse(
      String ownerType,
      UUID ownerId,
      UUID warehouseId,
      Long ownerRevision,
      Long aggregateVersion,
      UUID proofEventId,
      Boolean active) {}
}
