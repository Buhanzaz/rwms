package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    when(fixture.logistics.createRentalInquiry(fixture.conversationId, clientId, null, "bearer"))
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
    verify(fixture.logistics).createRentalInquiry(fixture.conversationId, clientId, null, "bearer");
  }

  @Test
  void bookedOrderConversationIsArchivedBeforeARepeatCreateUsesAFreshInquiry() {
    Fixture fixture = new Fixture();
    UUID orderId = UUID.randomUUID();
    UUID oldConversationId = UUID.randomUUID();
    UUID oldInquiryId = UUID.randomUUID();
    UUID newConversationId = UUID.randomUUID();
    UUID newInquiryId = UUID.randomUUID();
    UUID clientId = fixture.conversation.getClientId();
    AssistantConversation oldConversation =
        AssistantConversation.create(
            oldConversationId,
            fixture.ownerId,
            clientId,
            oldInquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    AssistantConversation freshConversation =
        AssistantConversation.create(
            newConversationId,
            fixture.ownerId,
            clientId,
            newInquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    LogisticsClient.InquiryBootstrap freshBootstrap =
        new LogisticsClient.InquiryBootstrap(
            newInquiryId, clientId, "ACTIVE", "INDIVIDUAL", "Иван Иванов");
    when(fixture.creationStore.activeForOrder(orderId, fixture.ownerId))
        .thenReturn(oldConversation);
    when(fixture.logistics.readRentalInquiryContext(oldInquiryId, "bearer"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new LogisticsClient.RentalInquiryContext(
                  oldInquiryId, clientId, orderId, fixture.warehouseId, "BOOKED");
            });
    when(fixture.creationStore.archiveTerminalForOrder(
            fixture.ownerId, orderId, clientId, oldConversationId, oldInquiryId))
        .thenReturn(null);
    when(fixture.logistics.createRentalInquiry(
            newConversationId, clientId, null, orderId, "bearer"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return freshBootstrap;
            });
    when(fixture.creationStore.createOrValidate(
            newConversationId,
            fixture.ownerId,
            freshBootstrap,
            clientId,
            null,
            orderId))
        .thenReturn(freshConversation);
    fixture.stubResponse(freshConversation);

    AssistantApiModels.CreateConversationResponse result =
        fixture.service.create(
            fixture.ownerId,
            new AssistantApiModels.CreateConversationRequest(
                newConversationId, clientId, null, orderId),
            "bearer");

    assertThat(result.conversation().id()).isEqualTo(newConversationId);
    assertThat(result.inquiry().id()).isEqualTo(newInquiryId);
    verify(fixture.creationStore)
        .archiveTerminalForOrder(
            fixture.ownerId, orderId, clientId, oldConversationId, oldInquiryId);
  }

  @Test
  void newerActiveOrderConversationReturnedByTheFenceIsRecheckedBeforeReuse() {
    Fixture fixture = new Fixture();
    UUID orderId = UUID.randomUUID();
    UUID oldConversationId = UUID.randomUUID();
    UUID oldInquiryId = UUID.randomUUID();
    UUID newerConversationId = UUID.randomUUID();
    UUID newerInquiryId = UUID.randomUUID();
    UUID clientId = fixture.conversation.getClientId();
    AssistantConversation oldConversation =
        AssistantConversation.create(
            oldConversationId,
            fixture.ownerId,
            clientId,
            oldInquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    AssistantConversation newerConversation =
        AssistantConversation.create(
            newerConversationId,
            fixture.ownerId,
            clientId,
            newerInquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    when(fixture.creationStore.activeForOrder(orderId, fixture.ownerId))
        .thenReturn(oldConversation);
    when(fixture.logistics.readRentalInquiryContext(oldInquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                oldInquiryId, clientId, orderId, fixture.warehouseId, "BOOKED"));
    when(fixture.creationStore.archiveTerminalForOrder(
            fixture.ownerId, orderId, clientId, oldConversationId, oldInquiryId))
        .thenReturn(newerConversation);
    when(fixture.logistics.readRentalInquiryContext(newerInquiryId, "bearer"))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new LogisticsClient.RentalInquiryContext(
                  newerInquiryId, clientId, orderId, fixture.warehouseId, "ACTIVE");
            });
    fixture.stubResponse(newerConversation);

    AssistantApiModels.CreateConversationResponse result =
        fixture.service.create(
            fixture.ownerId,
            new AssistantApiModels.CreateConversationRequest(
                UUID.randomUUID(), clientId, null, orderId),
            "bearer");

    assertThat(result.conversation().id()).isEqualTo(newerConversationId);
    verify(fixture.logistics).readRentalInquiryContext(newerInquiryId, "bearer");
  }

  @Test
  void newerTerminalOrderConversationReturnedByTheFenceIsArchivedInsteadOfListed() {
    Fixture fixture = new Fixture();
    UUID orderId = UUID.randomUUID();
    UUID oldConversationId = UUID.randomUUID();
    UUID oldInquiryId = UUID.randomUUID();
    UUID newerConversationId = UUID.randomUUID();
    UUID newerInquiryId = UUID.randomUUID();
    UUID clientId = fixture.conversation.getClientId();
    AssistantConversation oldConversation =
        AssistantConversation.create(
            oldConversationId,
            fixture.ownerId,
            clientId,
            oldInquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    AssistantConversation newerConversation =
        AssistantConversation.create(
            newerConversationId,
            fixture.ownerId,
            clientId,
            newerInquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    when(fixture.conversations
            .findByOwnerSubjectIdAndRentalOrderIdAndArchivedFalseOrderByUpdatedAtDesc(
                fixture.ownerId, orderId))
        .thenReturn(List.of(oldConversation));
    when(fixture.logistics.readRentalInquiryContext(oldInquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                oldInquiryId, clientId, orderId, fixture.warehouseId, "BOOKED"));
    when(fixture.creationStore.archiveTerminalForOrder(
            fixture.ownerId, orderId, clientId, oldConversationId, oldInquiryId))
        .thenReturn(newerConversation);
    when(fixture.logistics.readRentalInquiryContext(newerInquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                newerInquiryId, clientId, orderId, fixture.warehouseId, "ARCHIVED"));
    when(fixture.creationStore.archiveTerminalForOrder(
            fixture.ownerId, orderId, clientId, newerConversationId, newerInquiryId))
        .thenReturn(null);

    assertThat(fixture.service.list(fixture.ownerId, orderId, "bearer")).isEmpty();

    verify(fixture.creationStore)
        .archiveTerminalForOrder(
            fixture.ownerId, orderId, clientId, newerConversationId, newerInquiryId);
  }

  @Test
  void reconciliationCycleFailsClosedWithoutReturningATerminalConversation() {
    Fixture fixture = new Fixture();
    UUID orderId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID clientId = fixture.conversation.getClientId();
    AssistantConversation candidate =
        AssistantConversation.create(
            conversationId,
            fixture.ownerId,
            clientId,
            inquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    when(fixture.conversations
            .findByOwnerSubjectIdAndRentalOrderIdAndArchivedFalseOrderByUpdatedAtDesc(
                fixture.ownerId, orderId))
        .thenReturn(List.of(candidate));
    when(fixture.logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, clientId, orderId, fixture.warehouseId, "BOOKED"));
    when(fixture.creationStore.archiveTerminalForOrder(
            fixture.ownerId, orderId, clientId, conversationId, inquiryId))
        .thenReturn(candidate);

    assertThatThrownBy(() -> fixture.service.list(fixture.ownerId, orderId, "bearer"))
        .isInstanceOf(AssistantUpstreamException.class);
  }

  @Test
  void mismatchedOrderInquiryContextFailsClosedWithoutArchivingOrCreating() {
    Fixture fixture = new Fixture();
    UUID orderId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID clientId = fixture.conversation.getClientId();
    AssistantConversation candidate =
        AssistantConversation.create(
            conversationId,
            fixture.ownerId,
            clientId,
            inquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    when(fixture.creationStore.activeForOrder(orderId, fixture.ownerId)).thenReturn(candidate);
    when(fixture.logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, UUID.randomUUID(), orderId, fixture.warehouseId, "BOOKED"));

    assertThatThrownBy(
            () ->
                fixture.service.create(
                    fixture.ownerId,
                    new AssistantApiModels.CreateConversationRequest(
                        UUID.randomUUID(), clientId, null, orderId),
                    "bearer"))
        .isInstanceOf(AssistantUpstreamException.class);

    verify(fixture.creationStore, never())
        .archiveTerminalForOrder(any(), any(), any(), any(), any());
  }

  @Test
  void orderInquiryDependencyFailureFailsClosedWithoutArchiving() {
    Fixture fixture = new Fixture();
    UUID orderId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID clientId = fixture.conversation.getClientId();
    AssistantConversation candidate =
        AssistantConversation.create(
            conversationId,
            fixture.ownerId,
            clientId,
            inquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    when(fixture.creationStore.activeForOrder(orderId, fixture.ownerId)).thenReturn(candidate);
    when(fixture.logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenThrow(new AssistantUpstreamException("logistics is unavailable"));

    assertThatThrownBy(
            () ->
                fixture.service.create(
                    fixture.ownerId,
                    new AssistantApiModels.CreateConversationRequest(
                        UUID.randomUUID(), clientId, null, orderId),
                    "bearer"))
        .isInstanceOf(AssistantUpstreamException.class);

    verify(fixture.creationStore, never())
        .archiveTerminalForOrder(any(), any(), any(), any(), any());
  }

  @Test
  void unsupportedOrderInquiryStateFailsClosedWithoutArchiving() {
    Fixture fixture = new Fixture();
    UUID orderId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID clientId = fixture.conversation.getClientId();
    AssistantConversation candidate =
        AssistantConversation.create(
            conversationId,
            fixture.ownerId,
            clientId,
            inquiryId,
            orderId,
            "INDIVIDUAL",
            "Иван Иванов");
    when(fixture.conversations
            .findByOwnerSubjectIdAndRentalOrderIdAndArchivedFalseOrderByUpdatedAtDesc(
                fixture.ownerId, orderId))
        .thenReturn(List.of(candidate));
    when(fixture.logistics.readRentalInquiryContext(inquiryId, "bearer"))
        .thenReturn(
            new LogisticsClient.RentalInquiryContext(
                inquiryId, clientId, orderId, fixture.warehouseId, "UNKNOWN"));

    assertThatThrownBy(() -> fixture.service.list(fixture.ownerId, orderId, "bearer"))
        .isInstanceOf(AssistantUpstreamException.class);
    verify(fixture.creationStore, never())
        .archiveTerminalForOrder(any(), any(), any(), any(), any());
  }

  @Test
  void unfilteredHistoryListDoesNotReadLogisticsPerConversation() {
    Fixture fixture = new Fixture();
    when(fixture.conversations.findByOwnerSubjectIdOrderByUpdatedAtDesc(fixture.ownerId))
        .thenReturn(List.of(fixture.conversation));

    assertThat(fixture.service.list(fixture.ownerId))
        .extracting(AssistantApiModels.ConversationResponse::id)
        .containsExactly(fixture.conversationId);

    verify(fixture.logistics, never()).readRentalInquiryContext(any(), any());
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

  @Test
  void archivedDetailKeepsHistoryWithoutReadingLiveLogisticsState() {
    Fixture fixture = new Fixture();
    fixture.conversation.archive();

    AssistantApiModels.ConversationDetailResponse detail =
        fixture.service.detail(fixture.ownerId, fixture.conversationId, "bearer");

    assertThat(detail.conversation().archived()).isTrue();
    assertThat(detail.lastSearchResult()).isNull();
    assertThat(detail.clarifications()).isEmpty();
    assertThat(detail.currentSelection()).isNull();
    verify(fixture.logistics, never()).readCabinSelection(fixture.inquiryId, "bearer");
    verify(fixture.clarifications, never()).current(fixture.conversationId);
  }

  @Test
  void terminalSelectionReadArchivesTheConversationAndReturnsReadOnlyHistory() {
    Fixture fixture = new Fixture();
    when(fixture.logistics.readCabinSelection(fixture.inquiryId, "bearer"))
        .thenThrow(new AssistantInquiryArchivedException());

    AssistantApiModels.ConversationDetailResponse detail =
        fixture.service.detail(fixture.ownerId, fixture.conversationId, "bearer");

    assertThat(detail.conversation().archived()).isTrue();
    assertThat(detail.lastSearchResult()).isNull();
    assertThat(detail.clarifications()).isEmpty();
    assertThat(detail.currentSelection()).isNull();
    verify(fixture.conversations).save(fixture.conversation);
    verify(fixture.clarifications, never()).current(fixture.conversationId);
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
    private final AssistantConversationRepository conversations =
        mock(AssistantConversationRepository.class);
    private final AssistantClarificationService clarifications =
        mock(AssistantClarificationService.class);
    private final AssistantResponseMapper responseMapper = mock(AssistantResponseMapper.class);
    private final AssistantConversation conversation;
    private final AssistantConversationService service;

    private Fixture() {
      conversation =
          AssistantConversation.create(
              conversationId, ownerId, UUID.randomUUID(), inquiryId, "INDIVIDUAL", "Иван Иванов");
      AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
      AssistantToolCallRepository toolCalls = mock(AssistantToolCallRepository.class);
      when(conversations.findByIdAndOwnerSubjectId(conversationId, ownerId))
          .thenReturn(Optional.of(conversation));
      when(conversations.findById(conversationId)).thenReturn(Optional.of(conversation));
      when(conversations.save(conversation)).thenReturn(conversation);
      when(logistics.readRentalInquiryContext(inquiryId, "bearer"))
          .thenReturn(
              new LogisticsClient.RentalInquiryContext(
                  inquiryId, conversation.getClientId(), null, warehouseId, "ACTIVE"));
      when(messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId))
          .thenReturn(List.of());
      when(toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId))
          .thenReturn(List.of());
      when(clarifications.current(conversationId)).thenReturn(List.of());
      stubResponse(conversation);
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

    private void stubResponse(AssistantConversation value) {
      when(responseMapper.toConversationResponse(value))
          .thenAnswer(
              ignored ->
                  new AssistantApiModels.ConversationResponse(
                      value.getId(),
                      value.getVersion(),
                      value.getClientId(),
                      value.getRentalInquiryId(),
                      value.getRentalOrderId(),
                      value.getClientType(),
                      value.getClientDisplayName(),
                      value.isArchived(),
                      value.getArchivedAt(),
                      value.getCreatedAt(),
                      value.getUpdatedAt()));
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
