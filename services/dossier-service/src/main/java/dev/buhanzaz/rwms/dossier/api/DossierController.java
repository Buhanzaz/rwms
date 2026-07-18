package dev.buhanzaz.rwms.dossier.api;

import dev.buhanzaz.rwms.dossier.domain.DossierActivityCode;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.service.DossierQueryService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DossierController {
  private final DossierQueryService queries;

  public DossierController(DossierQueryService queries) {
    this.queries = queries;
  }

  @GetMapping("/api/dossier/v1/cabins/{cabinId}")
  public DossierApiModels.CabinDossierResponse getCabinDossier(
      @PathVariable UUID cabinId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant occurredFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant occurredBefore,
      @RequestParam(required = false) Set<DossierActivityCode> activityCode,
      @RequestParam(required = false) Set<DossierProducer> sourceType,
      @RequestParam(required = false) UUID actorSubjectId,
      @AuthenticationPrincipal Jwt jwt) {
    return queries.get(
        cabinId,
        new DossierQueryService.Query(
            limit,
            after,
            occurredFrom,
            occurredBefore,
            withoutNull(activityCode),
            withoutNull(sourceType),
            actorSubjectId),
        jwt);
  }

  private static <T> Set<T> withoutNull(Set<T> values) {
    if (values == null || values.isEmpty()) return Set.of();
    java.util.LinkedHashSet<T> result = new java.util.LinkedHashSet<>(values);
    result.remove(null);
    return Set.copyOf(result);
  }
}
