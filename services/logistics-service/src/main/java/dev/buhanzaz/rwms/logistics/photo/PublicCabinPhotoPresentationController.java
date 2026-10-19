package dev.buhanzaz.rwms.logistics.photo;

import static dev.buhanzaz.rwms.logistics.photo.CabinPhotoPresentationApiModels.*;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous no-store boundary for presentation metadata and strictly scoped image streaming. */
@RestController
@Validated
@RequestMapping("/api/logistics/public/v1/cabin-photo-presentations")
@RequiredArgsConstructor
public class PublicCabinPhotoPresentationController {
  private final CabinPhotoPresentationService presentations;

  /** Resolves a non-expiring public snapshot without revealing private ownership facts. */
  @GetMapping("/{token}")
  public ResponseEntity<PublicCabinPhotoPresentationResponse> get(
      @PathVariable String token) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(presentations.publicPresentation(token));
  }

  /** Streams one exact image generation and variant that belongs to the signed presentation. */
  @GetMapping(value = "/{token}/media/{mediaId}/{generation}/{variant}", produces = "image/webp")
  public ResponseEntity<byte[]> media(
      @PathVariable String token,
      @PathVariable UUID mediaId,
      @PathVariable @Min(1) long generation,
      @PathVariable @Size(min = 1, max = 64) String variant) {
    LogisticsDependencyGateway.MediaContent content =
        presentations.media(token, mediaId, generation, variant);
    MediaType type;
    try {
      type = MediaType.parseMediaType(content.contentType());
    } catch (IllegalArgumentException exception) {
      type = MediaType.APPLICATION_OCTET_STREAM;
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(type)
        .header("X-Content-Type-Options", "nosniff")
        .body(content.bytes());
  }
}
