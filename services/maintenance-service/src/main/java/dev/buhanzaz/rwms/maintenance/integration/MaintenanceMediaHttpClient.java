package dev.buhanzaz.rwms.maintenance.integration;

import static dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.*;

import org.springframework.http.HttpHeaders;

/** Owns the exact private media owner-proof upsert boundary used by maintenance. */
final class MaintenanceMediaHttpClient {
  private static final String MEDIA_CLIENT = "maintenance-media";
  private static final String MEDIA_SCOPE = "media.maintenance";

  private final MaintenanceHttpTransport transport;
  private final String mediaOwnerProofUrl;

  MaintenanceMediaHttpClient(
      MaintenanceHttpTransport transport, MaintenanceDependencyProperties.Validated properties) {
    this.transport = transport;
    mediaOwnerProofUrl = MaintenanceHttpTransport.strip(properties.mediaBaseUrl().toString())
        + "/api/internal/media/v1/owner-proofs";
  }

  MediaOwnerProof upsertMediaOwnerProof(MediaOwnerProof proof) {
    if (proof == null) {
      throw new IllegalArgumentException("Media owner proof is required");
    }
    try {
      MediaOwnerProof response = transport.client().post()
          .uri(mediaOwnerProofUrl)
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(MEDIA_CLIENT, MEDIA_SCOPE))
          .body(proof)
          .retrieve()
          .body(MediaOwnerProof.class);
      if (!proof.equals(response)) {
        throw MaintenanceHttpTransport.malformed(
            "Media-service returned mismatched owner proof truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }
}
