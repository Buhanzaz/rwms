package dev.buhanzaz.rwms.assistant.api;

import dev.buhanzaz.rwms.assistant.config.AssistantSseProperties;
import dev.buhanzaz.rwms.assistant.service.AssistantAuthorizer;
import dev.buhanzaz.rwms.assistant.service.AssistantConversationService;
import dev.buhanzaz.rwms.assistant.service.AssistantTurnService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Public gateway-facing HTTP and SSE adapter for owner-scoped assistant conversations; it contains
 * no rental workflow logic.
 */
@RestController
@RequestMapping("/api/assistant/v1/conversations")
public class AssistantConversationController {
  private final AssistantAuthorizer authorizer;
  private final AssistantConversationService conversations;
  private final AssistantTurnService turns;
  private final AssistantSseProperties sse;

  public AssistantConversationController(
      AssistantAuthorizer authorizer,
      AssistantConversationService conversations,
      AssistantTurnService turns,
      AssistantSseProperties sse) {
    this.authorizer = authorizer;
    this.conversations = conversations;
    this.turns = turns;
    this.sse = sse;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public List<AssistantApiModels.ConversationResponse> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID rentalOrderId) {
    UUID owner = authorizer.requireRentalUser(jwt);
    return rentalOrderId == null
        ? conversations.list(owner)
        : conversations.list(owner, rentalOrderId, jwt.getTokenValue());
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AssistantApiModels.CreateConversationResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody AssistantApiModels.CreateConversationRequest request) {
    UUID owner = authorizer.requireRentalUser(jwt);
    return conversations.create(owner, request, jwt.getTokenValue());
  }

  @GetMapping(value = "/{conversationId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public AssistantApiModels.ConversationDetailResponse detail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId) {
    return conversations.detail(
        authorizer.requireRentalUser(jwt), conversationId, jwt.getTokenValue());
  }

  @DeleteMapping("/{conversationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId) {
    conversations.archive(authorizer.requireRentalUser(jwt), conversationId);
  }

  @PostMapping(
      value = "/{conversationId}/turns",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter turn(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID conversationId,
      @Valid @RequestBody AssistantApiModels.TurnRequest request) {
    UUID owner = authorizer.requireRentalUser(jwt);
    SseEmitter emitter = new SseEmitter(sse.timeout().toMillis());
    turns.stream(owner, conversationId, request, jwt.getTokenValue(), emitter);
    return emitter;
  }

  /** Keeps exactly the selected cabin IDs and releases every removed hold immediately. */
  @PutMapping(
      value = "/{conversationId}/selection",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AssistantApiModels.CabinSelectionResponse replaceSelection(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID conversationId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody AssistantApiModels.CabinSelectionRequest request) {
    return conversations.replaceSelection(
        authorizer.requireRentalUser(jwt),
        conversationId,
        idempotencyKey,
        request,
        jwt.getTokenValue());
  }
}
