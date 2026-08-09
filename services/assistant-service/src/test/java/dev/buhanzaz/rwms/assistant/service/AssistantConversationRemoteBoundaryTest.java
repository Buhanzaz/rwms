package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import dev.buhanzaz.rwms.assistant.mapper.AssistantResponseMapper;
import dev.buhanzaz.rwms.assistant.repository.AssistantConversationRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantMessageRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantToolCallRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Proves logistics selection I/O is outside assistant-service database transactions. */
class AssistantConversationRemoteBoundaryTest {
  @Test
  void conversationCreationInvokesLogisticsWithoutAnAssistantTransaction() {
    Fixture fixture = new Fixture();
    UUID clientId = fixture.conversation.getClientId();
    LogisticsClient.InquiryBootstrap bootstrap =
        new LogisticsClient.InquiryBootstrap(
            fixture.inquiryId, clientId, "ACTIVE", "INDIVIDUAL", "Иван Иванов");
    when(fixture.logistics.createRentalInquiry(
            fixture.conversationId, clientId, null, "bearer"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return bootstrap;
            });
    when(fixture.creationStore.createOrValidate(
            fixture.conversationId, fixture.ownerId, bootstrap, clientId, null))
        .thenReturn(fixture.conversation);

    AssistantApiModels.CreateConversationResponse result =
        fixture.service.create(
            fixture.ownerId,
            new AssistantApiModels.CreateConversationRequest(
                fixture.conversationId, clientId, null),
            "bearer");

    assertThat(result.conversation().id()).isEqualTo(fixture.conversationId);
    verify(fixture.logistics)
        .createRentalInquiry(fixture.conversationId, clientId, null, "bearer");
  }

  @Test
  void detailAndActiveSelectionReadsInvokeLogisticsWithoutAnAssistantTransaction() {
    Fixture fixture = new Fixture();
    when(fixture.logistics.readCabinSelection(fixture.inquiryId, "bearer"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return fixture.selection(List.of(fixture.rentalItemId));
            });
    when(fixture.logistics.findClientPresentation(fixture.inquiryId, "bearer"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Optional.empty();
            });

    AssistantApiModels.ConversationDetailResponse detail =
        fixture.service.detail(fixture.ownerId, fixture.conversationId, "bearer");

    assertThat(detail.currentSelection()).isNotNull();
    assertThat(
            fixture.service.hasActiveSearchResult(
                fixture.ownerId, fixture.conversationId, "bearer"))
        .isTrue();
    verify(fixture.logistics).findClientPresentation(fixture.inquiryId, "bearer");
  }

  @Test
  void publicSelectionReplacementInvokesLogisticsWithoutAnAssistantTransaction() {
    Fixture fixture = new Fixture();
    UUID idempotencyKey = UUID.randomUUID();
    when(fixture.logistics.replaceCabinSelection(
            fixture.inquiryId,
            idempotencyKey,
            fixture.warehouseId,
            List.of(fixture.rentalItemId),
            "bearer"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return fixture.selection(List.of(fixture.rentalItemId));
            });

    AssistantApiModels.CabinSelectionResponse result =
        fixture.service.replaceSelection(
            fixture.ownerId,
            fixture.conversationId,
            idempotencyKey,
            new AssistantApiModels.CabinSelectionRequest(
                fixture.warehouseId, List.of(fixture.rentalItemId)),
            "bearer");

    assertThat(result.rentalItemIds()).containsExactly(fixture.rentalItemId);
  }

  /** Minimal owner-scoped conversation fixture with a real selection boundary adapter. */
  private static final class Fixture {
    private final UUID ownerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID inquiryId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID rentalItemId = UUID.randomUUID();
    private final LogisticsClient logistics = mock(LogisticsClient.class);
    private final AssistantConversationCreationStore creationStore =
        mock(AssistantConversationCreationStore.class);
    private final AssistantConversation conversation;
    private final AssistantConversationService service;

    private Fixture() {
      conversation =
          AssistantConversation.create(
              conversationId,
              ownerId,
              UUID.randomUUID(),
              inquiryId,
              "INDIVIDUAL",
              "Иван Иванов");
      AssistantConversationRepository conversations =
          mock(AssistantConversationRepository.class);
      AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
      AssistantToolCallRepository toolCalls = mock(AssistantToolCallRepository.class);
      AssistantResponseMapper responseMapper = mock(AssistantResponseMapper.class);
      AssistantClarificationService clarifications = mock(AssistantClarificationService.class);
      when(conversations.findByIdAndOwnerSubjectId(conversationId, ownerId))
          .thenReturn(Optional.of(conversation));
      when(messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId))
          .thenReturn(List.of());
      when(toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId))
          .thenReturn(List.of());
      when(clarifications.current(conversationId)).thenReturn(List.of());
      when(responseMapper.toConversationResponse(conversation))
          .thenReturn(
              new AssistantApiModels.ConversationResponse(
                  conversationId,
                  0,
                  conversation.getClientId(),
                  inquiryId,
                  "INDIVIDUAL",
                  "Иван Иванов",
                  false,
                  null,
                  null,
                  null));
      service =
          new AssistantConversationService(
              conversations,
              creationStore,
              messages,
              toolCalls,
              logistics,
              responseMapper,
              clarifications,
              new AssistantSelectionService(logistics, new ObjectMapper()));
    }

    private LogisticsClient.CabinSelection selection(List<UUID> ids) {
      return new LogisticsClient.CabinSelection(
          inquiryId,
          warehouseId,
          ids.isEmpty() ? null : OffsetDateTime.parse("2030-08-09T12:00:00Z"),
          ids,
          ids.stream()
              .map(
                  id ->
                      (tools.jackson.databind.JsonNode)
                          new ObjectMapper()
                              .createObjectNode()
                              .put("id", id.toString())
                              .put("number", "CAB-1"))
              .toList());
    }
  }
}
