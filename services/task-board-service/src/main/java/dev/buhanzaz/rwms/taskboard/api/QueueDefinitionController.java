package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionOrderRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionRequest;

import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api")
public class QueueDefinitionController {
  private final RegistryService service;
  private final WarehouseAccessAuthorizer access;

  @GetMapping("/queue-definitions")
  public List<QueueDefinitionDto> list(@AuthenticationPrincipal Jwt jwt) {
    access.requireUserScope(jwt, "rwms.read");
    return service.listQueueDefinitions();
  }

  @PostMapping("/queue-definitions")
  @ResponseStatus(HttpStatus.CREATED)
  public QueueDefinitionDto create(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody QueueDefinitionRequest request) {
    requireWrite(jwt);
    return service.createQueueDefinition(request);
  }

  @PutMapping("/queue-definitions/{id}")
  public QueueDefinitionDto update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody QueueDefinitionRequest request) {
    requireWrite(jwt);
    return service.updateQueueDefinition(id, request);
  }

  @PutMapping("/queue-definitions/order")
  public List<QueueDefinitionDto> reorder(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody QueueDefinitionOrderRequest request) {
    requireWrite(jwt);
    return service.reorderQueueDefinitions(request);
  }

  @DeleteMapping("/queue-definitions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    requireWrite(jwt);
    service.deleteQueueDefinition(id, expectedVersion);
  }

  private void requireWrite(Jwt jwt) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireGlobalManagement(jwt);
  }
}
