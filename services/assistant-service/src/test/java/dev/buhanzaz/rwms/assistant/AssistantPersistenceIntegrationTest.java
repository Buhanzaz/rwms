package dev.buhanzaz.rwms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationKind;
import dev.buhanzaz.rwms.assistant.domain.AssistantClarificationQuestion;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.domain.AssistantMessage;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus;
import dev.buhanzaz.rwms.assistant.eventing.RentalInquiryArchiveService;
import dev.buhanzaz.rwms.assistant.eventing.RentalInquiryBookedEventParser;
import dev.buhanzaz.rwms.assistant.integration.LogisticsClient;
import dev.buhanzaz.rwms.assistant.mapper.AssistantResponseMapperImpl;
import dev.buhanzaz.rwms.assistant.repository.AssistantClarificationQuestionRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantConversationRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantEventInboxRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantMessageRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantToolCallRepository;
import dev.buhanzaz.rwms.assistant.service.AssistantClarificationService;
import dev.buhanzaz.rwms.assistant.service.AssistantConflictException;
import dev.buhanzaz.rwms.assistant.service.AssistantConversationCreationStore;
import dev.buhanzaz.rwms.assistant.service.AssistantConversationService;
import dev.buhanzaz.rwms.assistant.service.AssistantNotFoundException;
import dev.buhanzaz.rwms.assistant.service.AssistantSelectionService;
import dev.buhanzaz.rwms.assistant.service.AssistantToolDefinitions;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Exercises Flyway/JPA persistence, durable chat state and idempotent creation boundaries. */
@SpringBootTest(
    classes = AssistantServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.assistant.llm.api-key=test-key",
      "rwms.assistant.llm.require-api-key-on-startup=false",
      "rwms.assistant.kafka.enabled=false"
    })
@ActiveProfiles("test")
@Transactional
class AssistantPersistenceIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssistantConversationRepository conversations;
  @Autowired AssistantMessageRepository messages;
  @Autowired AssistantToolCallRepository toolCalls;
  @Autowired AssistantClarificationQuestionRepository clarificationQuestions;
  @Autowired AssistantEventInboxRepository inbox;
  @Autowired EntityManager entityManager;
  @Autowired AssistantConversationCreationStore creationStore;
  @Autowired AssistantConversationService liveConversationService;
  @Autowired AssistantClarificationService liveClarificationService;
  @Autowired RentalInquiryArchiveService liveArchiveService;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void flywayValidatesConversationMessageToolAndInboxPersistenceWithIdempotentArchive() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    AssistantConversation conversation =
        conversations.saveAndFlush(
            AssistantConversation.create(
                conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    AssistantMessage user =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Need a cabin"));
    AssistantToolCall tool =
        toolCalls.saveAndFlush(
            AssistantToolCall.start(
                conversationId,
                user.getId(),
                "provider-call-1",
                "list_available_cabin_facets",
                new ObjectMapper().createObjectNode()));
    tool.complete(new ObjectMapper().createObjectNode().put("tool", "list_available_cabin_facets"));
    toolCalls.saveAndFlush(tool);

    AssistantConversationService service =
        service(new CountingLogisticsClient(inquiryId, conversation.getClientId()));
    RentalInquiryArchiveService archive = new RentalInquiryArchiveService(inbox, service);
    RentalInquiryBookedEventParser parser = new RentalInquiryBookedEventParser(new ObjectMapper());
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    String event = bookingEnvelope(eventId, inquiryId, conversationId, orderId);
    RentalInquiryBookedEventParser.ParsedEvent parsed =
        parser.parse(
            "rwms.logistics.rental-inquiry.events.v1", 0, 42, conversationId.toString(), event);

    assertThat(archive.stage(parsed)).isEqualTo(RentalInquiryArchiveService.StageOutcome.READY);
    assertThat(archive.claimNextAttempt(eventId).attemptNumber()).isOne();
    assertThat(archive.process(parsed))
        .isEqualTo(RentalInquiryArchiveService.ProcessingOutcome.PROCESSED);
    assertThat(archive.stage(parsed)).isEqualTo(RentalInquiryArchiveService.StageOutcome.DUPLICATE);

    assertThat(conversations.findById(conversationId).orElseThrow().isArchived()).isTrue();
    assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId))
        .extracting(AssistantMessage::getContent)
        .containsExactly("Need a cabin");
    assertThat(toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId))
        .extracting(AssistantToolCall::getStatus)
        .containsExactly(dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus.COMPLETED);
    assertThat(inbox.count()).isEqualTo(1);
  }

  @Test
  void
      stableConversationIdReplaysWithoutSecondLogisticsCreateAndOwnerCannotReadAnotherConversation() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    CountingLogisticsClient logistics = new CountingLogisticsClient(inquiryId, clientId);
    AssistantConversationService service = service(logistics);
    AssistantApiModels.CreateConversationRequest request =
        new AssistantApiModels.CreateConversationRequest(conversationId, clientId, null);

    service.create(owner, request, "current-user-bearer");
    service.create(owner, request, "current-user-bearer");

    assertThat(logistics.creates.get()).isEqualTo(1);
    assertThat(conversations.count()).isEqualTo(1);
    assertThatThrownBy(() -> service.detail(UUID.randomUUID(), conversationId))
        .isInstanceOf(AssistantNotFoundException.class);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentConversationCreationKeepsRemoteCallsOutsideTransactionsAndCreatesOneLocalRow() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    CountingLogisticsClient logistics = new CountingLogisticsClient(inquiryId, clientId);
    AssistantConversationService service = service(logistics);
    AssistantApiModels.CreateConversationRequest request =
        new AssistantApiModels.CreateConversationRequest(conversationId, clientId, null);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<AssistantApiModels.CreateConversationResponse> first =
          CompletableFuture.supplyAsync(
              () -> createAfter(start, service, owner, request), executor);
      CompletableFuture<AssistantApiModels.CreateConversationResponse> second =
          CompletableFuture.supplyAsync(
              () -> createAfter(start, service, owner, request), executor);
      start.countDown();

      List<AssistantApiModels.CreateConversationResponse> results =
          List.of(first.join(), second.join());
      assertThat(results)
          .extracting(result -> result.conversation().id())
          .containsOnly(conversationId);
      assertThat(results).extracting(result -> result.inquiry().id()).containsOnly(inquiryId);
      assertThat(conversations.findAll())
          .filteredOn(conversation -> conversation.getId().equals(conversationId))
          .hasSize(1);
      assertThat(logistics.creates.get()).isBetween(1, 2);
      assertThat(logistics.createTransactionStates).isNotEmpty().containsOnly(false);
    } finally {
      executor.shutdownNow();
      conversations.findById(conversationId).ifPresent(conversations::delete);
    }
  }

  @Test
  void newClientReplayIsRevalidatedByTheOwningLogisticsInquiry() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    CountingLogisticsClient logistics = new CountingLogisticsClient(inquiryId, clientId);
    AssistantConversationService service = service(logistics);
    AssistantApiModels.CreateConversationRequest request =
        new AssistantApiModels.CreateConversationRequest(
            conversationId,
            null,
            new AssistantApiModels.NewClientRequest(
                "LEGAL_ENTITY", "ООО Север", "+79990000000", "Иван Петров", null, null, null));

    service.create(owner, request, "current-user-bearer");
    service.create(owner, request, "current-user-bearer");

    assertThat(logistics.creates.get()).isEqualTo(2);
    assertThat(conversations.count()).isEqualTo(1);
  }

  @Test
  void orderLinkedCreateOpensTheOneActiveConversationAndListFilterReturnsIt() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    CountingLogisticsClient logistics = new CountingLogisticsClient(UUID.randomUUID(), clientId);
    AssistantConversationService conversationService = service(logistics);

    AssistantApiModels.CreateConversationResponse created =
        conversationService.create(
            owner,
            new AssistantApiModels.CreateConversationRequest(
                UUID.randomUUID(), clientId, null, orderId),
            "current-user-bearer");
    AssistantApiModels.CreateConversationResponse opened =
        conversationService.create(
            owner,
            new AssistantApiModels.CreateConversationRequest(
                UUID.randomUUID(), clientId, null, orderId),
            "current-user-bearer");

    assertThat(opened.conversation().id()).isEqualTo(created.conversation().id());
    assertThat(opened.conversation().rentalOrderId()).isEqualTo(orderId);
    assertThat(logistics.creates).hasValue(1);
    assertThat(logistics.requestedRentalOrderIds).containsExactly(orderId);
    assertThat(conversationService.list(owner, orderId, "current-user-bearer"))
        .extracting(
            AssistantApiModels.ConversationResponse::id,
            AssistantApiModels.ConversationResponse::rentalOrderId)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(created.conversation().id(), orderId));
    assertThat(
            conversationService.list(UUID.randomUUID(), orderId, "current-user-bearer"))
        .isEmpty();
    assertThatThrownBy(
            () ->
                conversationService.create(
                    UUID.randomUUID(),
                    new AssistantApiModels.CreateConversationRequest(
                        UUID.randomUUID(), clientId, null, orderId),
                    "current-user-bearer"))
        .isInstanceOf(AssistantNotFoundException.class);

    conversationService.archive(owner, created.conversation().id());
    CountingLogisticsClient replacementLogistics =
        new CountingLogisticsClient(UUID.randomUUID(), clientId);
    AssistantConversationService replacementService = service(replacementLogistics);
    AssistantApiModels.CreateConversationResponse replacement =
        replacementService.create(
            owner,
            new AssistantApiModels.CreateConversationRequest(
                UUID.randomUUID(), clientId, null, orderId),
            "current-user-bearer");

    assertThat(replacement.conversation().id()).isNotEqualTo(created.conversation().id());
    assertThat(logistics.creates).hasValue(1);
    assertThat(replacementLogistics.creates).hasValue(1);
    assertThat(replacementService.list(owner, orderId, "current-user-bearer"))
        .extracting(AssistantApiModels.ConversationResponse::id)
        .containsExactly(replacement.conversation().id());
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentOrderLinkedCreatesResolveToOneActiveConversation() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    CountingLogisticsClient logistics = new CountingLogisticsClient(UUID.randomUUID(), clientId);
    AssistantConversationService conversationService = service(logistics);
    AssistantApiModels.CreateConversationRequest firstRequest =
        new AssistantApiModels.CreateConversationRequest(
            UUID.randomUUID(), clientId, null, orderId);
    AssistantApiModels.CreateConversationRequest secondRequest =
        new AssistantApiModels.CreateConversationRequest(
            UUID.randomUUID(), clientId, null, orderId);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<AssistantApiModels.CreateConversationResponse> first =
          CompletableFuture.supplyAsync(
              () -> createAfter(start, conversationService, owner, firstRequest), executor);
      CompletableFuture<AssistantApiModels.CreateConversationResponse> second =
          CompletableFuture.supplyAsync(
              () -> createAfter(start, conversationService, owner, secondRequest), executor);
      start.countDown();

      List<AssistantApiModels.CreateConversationResponse> results =
          List.of(first.join(), second.join());
      assertThat(results)
          .extracting(result -> result.conversation().id())
          .containsOnly(results.getFirst().conversation().id());
      assertThat(conversations.findAll())
          .filteredOn(
              conversation ->
                  orderId.equals(conversation.getRentalOrderId()) && !conversation.isArchived())
          .hasSize(1);
      assertThat(logistics.createTransactionStates).isNotEmpty().containsOnly(false);
    } finally {
      executor.shutdownNow();
      conversations.findAll().stream()
          .filter(conversation -> orderId.equals(conversation.getRentalOrderId()))
          .forEach(conversations::delete);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void orderListReconcilesEventLagAndLateBookedEventCannotArchiveTheFreshConversation() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    OrderLifecycleLogisticsClient logistics = new OrderLifecycleLogisticsClient(clientId);
    AssistantConversationService conversationService = service(logistics);
    AssistantApiModels.CreateConversationResponse old =
        conversationService.create(
            owner,
            new AssistantApiModels.CreateConversationRequest(
                UUID.randomUUID(), clientId, null, orderId),
            "current-user-bearer");
    logistics.changeState(old.inquiry().id(), "BOOKED");

    try {
      assertThat(conversationService.list(owner, orderId, "current-user-bearer")).isEmpty();
      assertThat(conversations.findById(old.conversation().id()).orElseThrow().isArchived())
          .isTrue();

      AssistantApiModels.CreateConversationResponse fresh =
          conversationService.create(
              owner,
              new AssistantApiModels.CreateConversationRequest(
                  UUID.randomUUID(), clientId, null, orderId),
              "current-user-bearer");
      assertThat(fresh.conversation().id()).isNotEqualTo(old.conversation().id());
      assertThat(fresh.inquiry().id()).isNotEqualTo(old.inquiry().id());

      RentalInquiryBookedEventParser parser =
          new RentalInquiryBookedEventParser(new ObjectMapper());
      RentalInquiryBookedEventParser.ParsedEvent lateEvent =
          parser.parse(
              "rwms.logistics.rental-inquiry.events.v1",
              0,
              9_999,
              old.conversation().id().toString(),
              bookingEnvelope(
                  eventId,
                  old.inquiry().id(),
                  old.conversation().id(),
                  orderId));
      assertThat(liveArchiveService.stage(lateEvent))
          .isEqualTo(RentalInquiryArchiveService.StageOutcome.READY);
      assertThat(liveArchiveService.claimNextAttempt(eventId).attemptNumber()).isOne();
      assertThat(liveArchiveService.process(lateEvent))
          .isEqualTo(RentalInquiryArchiveService.ProcessingOutcome.PROCESSED);
      assertThat(liveArchiveService.stage(lateEvent))
          .isEqualTo(RentalInquiryArchiveService.StageOutcome.DUPLICATE);

      assertThat(conversations.findById(fresh.conversation().id()).orElseThrow().isArchived())
          .isFalse();
      assertThat(conversationService.list(owner, orderId, "current-user-bearer"))
          .extracting(AssistantApiModels.ConversationResponse::id)
          .containsExactly(fresh.conversation().id());
      assertThat(logistics.readTransactionStates).isNotEmpty().containsOnly(false);
    } finally {
      inbox.deleteById(eventId);
      conversations.findAll().stream()
          .filter(conversation -> orderId.equals(conversation.getRentalOrderId()))
          .forEach(conversations::delete);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentRepeatCreatesReconcileOneBookedConversationIntoOneFreshActiveConversation() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OrderLifecycleLogisticsClient logistics = new OrderLifecycleLogisticsClient(clientId);
    AssistantConversationService conversationService = service(logistics);
    AssistantApiModels.CreateConversationResponse old =
        conversationService.create(
            owner,
            new AssistantApiModels.CreateConversationRequest(
                UUID.randomUUID(), clientId, null, orderId),
            "current-user-bearer");
    logistics.changeState(old.inquiry().id(), "BOOKED");
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<AssistantApiModels.CreateConversationResponse> first =
          CompletableFuture.supplyAsync(
              () ->
                  createAfter(
                      start,
                      conversationService,
                      owner,
                      new AssistantApiModels.CreateConversationRequest(
                          UUID.randomUUID(), clientId, null, orderId)),
              executor);
      CompletableFuture<AssistantApiModels.CreateConversationResponse> second =
          CompletableFuture.supplyAsync(
              () ->
                  createAfter(
                      start,
                      conversationService,
                      owner,
                      new AssistantApiModels.CreateConversationRequest(
                          UUID.randomUUID(), clientId, null, orderId)),
              executor);
      start.countDown();

      List<AssistantApiModels.CreateConversationResponse> results =
          List.of(first.join(), second.join());
      assertThat(results)
          .extracting(result -> result.conversation().id())
          .containsOnly(results.getFirst().conversation().id());
      assertThat(results)
          .extracting(result -> result.inquiry().id())
          .containsOnly(results.getFirst().inquiry().id());
      assertThat(results.getFirst().conversation().id()).isNotEqualTo(old.conversation().id());
      assertThat(conversations.findById(old.conversation().id()).orElseThrow().isArchived())
          .isTrue();
      assertThat(conversations.findAll())
          .filteredOn(
              conversation ->
                  orderId.equals(conversation.getRentalOrderId()) && !conversation.isArchived())
          .singleElement();
      assertThat(logistics.createTransactionStates).isNotEmpty().containsOnly(false);
      assertThat(logistics.readTransactionStates).isNotEmpty().containsOnly(false);
    } finally {
      executor.shutdownNow();
      conversations.findAll().stream()
          .filter(conversation -> orderId.equals(conversation.getRentalOrderId()))
          .forEach(conversations::delete);
    }
  }

  @Test
  void terminalArchiveFenceNeverArchivesANewerActiveConversationForTheSameOrder() {
    UUID owner = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID oldConversationId = UUID.randomUUID();
    UUID oldInquiryId = UUID.randomUUID();
    AssistantConversation old =
        AssistantConversation.create(
            oldConversationId,
            owner,
            clientId,
            oldInquiryId,
            orderId,
            "PERSON",
            "Client summary");
    old.archive();
    conversations.saveAndFlush(old);
    AssistantConversation newer =
        conversations.saveAndFlush(
            AssistantConversation.create(
                UUID.randomUUID(),
                owner,
                clientId,
                UUID.randomUUID(),
                orderId,
                "PERSON",
                "Client summary"));

    AssistantConversation winner =
        creationStore.archiveTerminalForOrder(
            owner, orderId, clientId, oldConversationId, oldInquiryId);

    assertThat(winner.getId()).isEqualTo(newer.getId());
    assertThat(conversations.findById(newer.getId()).orElseThrow().isArchived()).isFalse();
  }

  @Test
  void osbAndLdspQuestionsPersistAsOneQueueAndRejectReverseOrderAfterReload() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    AssistantMessage turnMessage =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Нужны бытовки ОСБ и ЛДСП"));
    AssistantToolCall toolCall =
        toolCalls.saveAndFlush(
            AssistantToolCall.start(
                conversationId,
                turnMessage.getId(),
                "clarifications-osb-ldsp",
                AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
                new ObjectMapper().createObjectNode()));
    String osbBranch = "search:" + UUID.randomUUID() + ":osb:type";
    String ldspBranch = "search:" + UUID.randomUUID() + ":ldsp:type";
    AssistantClarificationService clarificationService =
        new AssistantClarificationService(clarificationQuestions);
    List<AssistantApiModels.ClarificationQuestionResponse> created =
        clarificationService.create(
            conversationId,
            turnMessage.getId(),
            toolCall.getId(),
            warehouseId,
            List.of(
                new AssistantClarificationService.QuestionDraft(
                    osbBranch,
                    AssistantClarificationKind.CABIN_TYPE,
                    "Какой тип бытовки нужен для ОСБ?",
                    null,
                    List.of(
                        new AssistantClarificationService.OptionDraft("Модуль", "Модуль"),
                        new AssistantClarificationService.OptionDraft(
                            "Пост охраны", "Пост охраны"))),
                new AssistantClarificationService.QuestionDraft(
                    ldspBranch,
                    AssistantClarificationKind.CABIN_TYPE,
                    "Какой тип бытовки нужен для ЛДСП?",
                    null,
                    List.of(
                        new AssistantClarificationService.OptionDraft("Модуль", "Модуль"),
                        new AssistantClarificationService.OptionDraft(
                            "Пост охраны", "Пост охраны")))));
    entityManager.flush();
    entityManager.clear();

    List<AssistantApiModels.ClarificationQuestionResponse> reloadedPending =
        clarificationService.current(conversationId);
    assertThat(reloadedPending)
        .extracting(
            AssistantApiModels.ClarificationQuestionResponse::branchKey,
            AssistantApiModels.ClarificationQuestionResponse::sequenceNumber,
            AssistantApiModels.ClarificationQuestionResponse::status)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(osbBranch, 1, "PENDING"));
    AssistantApiModels.ClarificationQuestionResponse reloadedOsb = reloadedPending.getFirst();
    assertThat(reloadedOsb.options()).containsExactlyElementsOf(created.get(0).options());

    AssistantClarificationQuestion queuedLdsp =
        clarificationQuestions.findByConversationIdOrderBySequenceNumberAsc(conversationId).get(1);
    UUID queuedLdspOptionId =
        UUID.fromString(queuedLdsp.getOptionsPayload().get(1).path("id").asText());

    AssistantConversationService conversationService =
        service(new CountingLogisticsClient(inquiryId, UUID.randomUUID()));
    assertThatThrownBy(
            () ->
                conversationService.beginTurn(
                    owner,
                    conversationId,
                    new AssistantApiModels.TurnRequest(
                        null,
                        new AssistantApiModels.ClarificationAnswerRequest(
                            queuedLdsp.getId(), queuedLdspOptionId))))
        .isInstanceOf(AssistantConflictException.class)
        .hasMessageContaining("out of order");
    assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)).hasSize(1);

    AssistantApiModels.ClarificationOptionResponse osbChoice = reloadedOsb.options().getFirst();
    AssistantConversationService.TurnStart osbAnswer =
        conversationService.beginTurn(
            owner,
            conversationId,
            new AssistantApiModels.TurnRequest(
                null,
                new AssistantApiModels.ClarificationAnswerRequest(
                    reloadedOsb.id(), osbChoice.id())));
    assertThat(osbAnswer.userMessage())
        .contains("Какой тип бытовки нужен для ОСБ?", osbChoice.label())
        .doesNotContain("search:", osbBranch);
    assertThat(osbAnswer.nextClarification())
        .isNotNull()
        .extracting(
            AssistantApiModels.ClarificationQuestionResponse::branchKey,
            AssistantApiModels.ClarificationQuestionResponse::sequenceNumber,
            AssistantApiModels.ClarificationQuestionResponse::status)
        .containsExactly(ldspBranch, 2, "PENDING");
    assertThat(clarificationService.current(conversationId))
        .extracting(
            AssistantApiModels.ClarificationQuestionResponse::branchKey,
            AssistantApiModels.ClarificationQuestionResponse::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(osbBranch, "ANSWERED"),
            org.assertj.core.groups.Tuple.tuple(ldspBranch, "PENDING"));
    entityManager.flush();
    entityManager.clear();

    AssistantApiModels.ClarificationQuestionResponse ldspReloaded =
        clarificationService.current(conversationId).stream()
            .filter(question -> ldspBranch.equals(question.branchKey()))
            .findFirst()
            .orElseThrow();
    AssistantApiModels.ClarificationOptionResponse ldspChoice = ldspReloaded.options().get(1);
    AssistantConversationService.TurnStart ldspAnswer =
        conversationService.beginTurn(
            owner,
            conversationId,
            new AssistantApiModels.TurnRequest(
                null,
                new AssistantApiModels.ClarificationAnswerRequest(
                    ldspReloaded.id(), ldspChoice.id())));
    entityManager.flush();
    entityManager.clear();

    assertThat(ldspAnswer.userMessage())
        .contains("Какой тип бытовки нужен для ЛДСП?", ldspChoice.label())
        .doesNotContain("search:", ldspBranch);
    assertThat(ldspAnswer.nextClarification()).isNull();
    assertThat(clarificationService.current(conversationId))
        .extracting(
            AssistantApiModels.ClarificationQuestionResponse::branchKey,
            AssistantApiModels.ClarificationQuestionResponse::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(osbBranch, "ANSWERED"),
            org.assertj.core.groups.Tuple.tuple(ldspBranch, "ANSWERED"));
    assertThat(
            messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
                .map(AssistantMessage::getContent)
                .toList())
        .anySatisfy(
            content ->
                assertThat(content)
                    .contains("Какой тип бытовки нужен для ОСБ?", osbChoice.label())
                    .doesNotContain("search:", osbBranch))
        .anySatisfy(
            content ->
                assertThat(content)
                    .contains("Какой тип бытовки нужен для ЛДСП?", ldspChoice.label())
                    .doesNotContain("search:", ldspBranch));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentDifferentAnswersAndReplayProduceOneTransitionAndOneUserMessage() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client"));
    AssistantMessage turnMessage =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Нужен тип"));
    AssistantToolCall toolCall =
        toolCalls.saveAndFlush(
            AssistantToolCall.start(
                conversationId,
                turnMessage.getId(),
                "one-question",
                AssistantToolDefinitions.REQUEST_CABIN_CLARIFICATIONS,
                new ObjectMapper().createObjectNode()));
    AssistantApiModels.ClarificationQuestionResponse head =
        liveClarificationService
            .create(
                conversationId,
                turnMessage.getId(),
                toolCall.getId(),
                warehouseId,
                List.of(
                    new AssistantClarificationService.QuestionDraft(
                        "ordered:one",
                        AssistantClarificationKind.CABIN_TYPE,
                        "Выберите тип",
                        null,
                        List.of(
                            new AssistantClarificationService.OptionDraft("Модуль", "Модуль"),
                            new AssistantClarificationService.OptionDraft(
                                "Пост охраны", "Пост охраны")))))
            .getFirst();
    AssistantApiModels.TurnRequest firstAnswer =
        new AssistantApiModels.TurnRequest(
            null,
            new AssistantApiModels.ClarificationAnswerRequest(
                head.id(), head.options().getFirst().id()));
    AssistantApiModels.TurnRequest secondAnswer =
        new AssistantApiModels.TurnRequest(
            null,
            new AssistantApiModels.ClarificationAnswerRequest(
                head.id(), head.options().get(1).id()));
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<Object> first =
          CompletableFuture.supplyAsync(
              () -> answerAfter(start, liveConversationService, owner, conversationId, firstAnswer),
              executor);
      CompletableFuture<Object> second =
          CompletableFuture.supplyAsync(
              () ->
                  answerAfter(start, liveConversationService, owner, conversationId, secondAnswer),
              executor);
      start.countDown();

      List<Object> results = List.of(first.join(), second.join());
      assertThat(results)
          .filteredOn(AssistantConversationService.TurnStart.class::isInstance)
          .hasSize(1);
      assertThat(results).filteredOn(AssistantConflictException.class::isInstance).hasSize(1);
      assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId))
          .extracting(AssistantMessage::getRole)
          .containsExactly(
              dev.buhanzaz.rwms.assistant.domain.AssistantMessageRole.USER,
              dev.buhanzaz.rwms.assistant.domain.AssistantMessageRole.USER);
      assertThat(liveClarificationService.current(conversationId))
          .singleElement()
          .satisfies(question -> assertThat(question.status()).isEqualTo("ANSWERED"));
      AssistantConversationService.TurnStart successful =
          results.stream()
              .filter(AssistantConversationService.TurnStart.class::isInstance)
              .map(AssistantConversationService.TurnStart.class::cast)
              .findFirst()
              .orElseThrow();
      AssistantApiModels.TurnRequest exactReplay =
          successful
                  .answeredClarification()
                  .answeredOptionId()
                  .equals(head.options().getFirst().id())
              ? firstAnswer
              : secondAnswer;
      assertThatThrownBy(
              () -> liveConversationService.beginTurn(owner, conversationId, exactReplay))
          .isInstanceOf(AssistantConflictException.class);
      assertThat(messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)).hasSize(2);
    } finally {
      executor.shutdownNow();
      clarificationQuestions.deleteAll(
          clarificationQuestions.findByConversationIdOrderBySequenceNumberAsc(conversationId));
      toolCalls.deleteAll(toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId));
      messages.deleteAll(messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId));
      conversations.findById(conversationId).ifPresent(conversations::delete);
    }
  }

  @Test
  void promptHistoryRetainsTerminalToolAuditCallsAfterTheirOwningUserMessage() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    AssistantMessage firstUser =
        messages.saveAndFlush(
            AssistantMessage.user(
                conversationId, "Покажи 10 свободных БК-1 с ДВП в Санкт-Петербурге"));
    ObjectMapper objectMapper = new ObjectMapper();
    AssistantToolCall firstTool =
        toolCalls.saveAndFlush(
            AssistantToolCall.start(
                conversationId,
                firstUser.getId(),
                "call_facets",
                AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS,
                objectMapper.createObjectNode()));
    firstTool.complete(
        objectMapper
            .createObjectNode()
            .put("tool", AssistantToolDefinitions.LIST_AVAILABLE_CABIN_FACETS));
    toolCalls.saveAndFlush(firstTool);
    AssistantToolCall secondTool =
        toolCalls.saveAndFlush(
            AssistantToolCall.start(
                conversationId,
                firstUser.getId(),
                "call_search",
                AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                objectMapper.createObjectNode().put("groups", "exact")));
    secondTool.complete(
        objectMapper
            .createObjectNode()
            .put("tool", AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS));
    toolCalls.saveAndFlush(secondTool);
    AssistantToolCall failedTool =
        toolCalls.saveAndFlush(
            AssistantToolCall.start(
                conversationId,
                firstUser.getId(),
                "call_failed",
                AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                objectMapper.createObjectNode()));
    failedTool.fail(
        "LOGISTICS_UNAVAILABLE",
        objectMapper.createObjectNode().put("code", "LOGISTICS_UNAVAILABLE"));
    toolCalls.saveAndFlush(failedTool);
    toolCalls.saveAndFlush(
        AssistantToolCall.start(
            conversationId,
            firstUser.getId(),
            "call_incomplete",
            AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
            objectMapper.createObjectNode()));
    messages.saveAndFlush(AssistantMessage.assistant(conversationId, "Точного совпадения нет."));
    messages.saveAndFlush(AssistantMessage.user(conversationId, "покажи все"));

    List<String> persistedOrder =
        toolCalls.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
            .filter(
                call ->
                    (call.getStatus() == AssistantToolCallStatus.COMPLETED
                            || call.getStatus() == AssistantToolCallStatus.FAILED)
                        && call.getResultPayload() != null)
            .map(AssistantToolCall::getProviderCallId)
            .toList();
    List<AssistantConversationService.PromptMessage> prompt =
        service(new CountingLogisticsClient(inquiryId, UUID.randomUUID()))
            .promptMessages(owner, conversationId);

    AssistantConversationService.PromptMessage firstPrompt =
        prompt.stream()
            .filter(value -> value.content().equals(firstUser.getContent()))
            .findFirst()
            .orElseThrow();
    assertThat(firstPrompt.toolCalls())
        .extracting(AssistantConversationService.PromptToolCall::providerCallId)
        .containsExactlyElementsOf(persistedOrder);
    assertThat(firstPrompt.toolCalls())
        .extracting(AssistantConversationService.PromptToolCall::result)
        .allSatisfy(result -> assertThat(result).isNotNull());
    assertThat(prompt)
        .filteredOn(value -> value.role().equals("assistant"))
        .singleElement()
        .satisfies(value -> assertThat(value.toolCalls()).isEmpty());
    assertThat(prompt)
        .filteredOn(value -> value.content().equals("покажи все"))
        .singleElement()
        .satisfies(value -> assertThat(value.toolCalls()).isEmpty());
  }

  @Test
  void
      detailMergesLatestTurnSearchCallsWithTheEarliestExpiryAndDropsEmptyExactGroupWhenAlternativesExist() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID firstCabinId = UUID.randomUUID();
    UUID secondCabinId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    AssistantMessage user =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Покажи две БК-1 с ТВП"));
    ObjectMapper objectMapper = new ObjectMapper();

    completeSearch(
        conversationId,
        user.getId(),
        "exact",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:20:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":"ДВП","dimensions":null,"quantity":2},
               "cabins":[]}
            ]}}
            """
                .formatted(warehouseId)));
    completeSearch(
        conversationId,
        user.getId(),
        "type-alternative",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"quantity":2},
               "cabins":[{"id":"%s","number":"БЫТ-111"}]}
            ]}}
            """
                .formatted(warehouseId, firstCabinId)));
    completeSearch(
        conversationId,
        user.getId(),
        "finish-alternative",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:15:00Z","groups":[
              {"group":{"cabinType":null,"finish":"ДВП","dimensions":null,"quantity":2},
               "cabins":[{"id":"%s","number":"БЫТ-071"}]}
            ]}}
            """
                .formatted(warehouseId, secondCabinId)));

    tools.jackson.databind.JsonNode result =
        service(new CountingLogisticsClient(inquiryId, UUID.randomUUID()))
            .detail(owner, conversationId)
            .lastSearchResult();
    tools.jackson.databind.JsonNode groups = result.path("data").path("groups");

    assertThat(result.path("data").path("expiresAt").asText()).isEqualTo("2030-07-27T12:10:00Z");
    assertThat(result.path("filterSuggestions").propertyNames())
        .containsExactlyInAnyOrder(
            "cabinTypes", "finishes", "dimensions", "categories", "characteristics");
    assertThat(result.path("filterSuggestions").path("cabinTypes")).isEmpty();
    assertThat(groups.size()).isEqualTo(2);
    assertThat(groups.get(0).path("group").path("cabinType").asText()).isEqualTo("БК-1");
    assertThat(groups.get(0).path("cabins").get(0).path("id").asText())
        .isEqualTo(firstCabinId.toString());
    assertThat(groups.get(1).path("group").path("finish").asText()).isEqualTo("ДВП");
    assertThat(groups.get(1).path("cabins").get(0).path("id").asText())
        .isEqualTo(secondCabinId.toString());
  }

  @Test
  void detailKeepsExactGapNoticesOnTheOwningUserTurnAndChainsOnlyAppendSearches() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID alternativeCabinId = UUID.randomUUID();
    UUID appendedCabinId = UUID.randomUUID();
    UUID replacementCabinId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    ObjectMapper objectMapper = new ObjectMapper();
    AssistantMessage firstTurn =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Покажи две БК-1 с ДВП"));

    completeSearch(
        conversationId,
        firstTurn.getId(),
        "exact-gap",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","resultMode":"REPLACE","notices":[
              {"code":"CABINS_NOT_FOUND","groups":[{"cabinType":"БК-1","finish":"ДВП","quantity":2}],"requestedQuantity":2,"foundQuantity":0}
            ],"data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:20:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":"ДВП","quantity":2},"cabins":[]}
            ]}}
            """
                .formatted(warehouseId)));
    completeSearch(
        conversationId,
        firstTurn.getId(),
        "alternative",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","resultMode":"REPLACE","notices":[],"data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","quantity":2},"cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, alternativeCabinId)));

    AssistantMessage appendTurn =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Да, добавь ещё БК-1"));
    completeSearch(
        conversationId,
        appendTurn.getId(),
        "append",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","resultMode":"APPEND","notices":[],"data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:15:00Z","groups":[
              {"group":{"cabinType":"БК-1","quantity":2},"cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, appendedCabinId)));

    CountingLogisticsClient logistics = new CountingLogisticsClient(inquiryId, UUID.randomUUID());
    AssistantConversationService service = service(logistics);
    AssistantApiModels.ConversationDetailResponse appendDetail =
        service.detail(owner, conversationId);
    AssistantApiModels.MessageResponse firstMessage =
        appendDetail.messages().stream()
            .filter(message -> message.id().equals(firstTurn.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(firstMessage.searchNotices())
        .singleElement()
        .satisfies(
            notice -> {
              assertThat(notice.path("code").asText()).isEqualTo("CABINS_NOT_FOUND");
              assertThat(notice.path("groups").get(0).path("quantity").intValue()).isEqualTo(2);
            });
    assertThat(service.hasActiveSearchResult(owner, conversationId, "current-user-bearer"))
        .isTrue();
    logistics.clientPresentation =
        Optional.of(new LogisticsClient.ClientPresentation(inquiryId, "ACTIVE"));
    assertThat(service.hasActiveSearchResult(owner, conversationId, "current-user-bearer"))
        .isFalse();
    logistics.clientPresentation =
        Optional.of(new LogisticsClient.ClientPresentation(inquiryId, "REVOKED"));
    assertThat(service.hasActiveSearchResult(owner, conversationId, "current-user-bearer"))
        .isFalse();
    assertThat(appendDetail.lastSearchResult().path("resultMode").asText()).isEqualTo("APPEND");
    assertThat(appendDetail.lastSearchResult().path("notices"))
        .singleElement()
        .satisfies(
            notice -> assertThat(notice.path("code").asText()).isEqualTo("CABINS_NOT_FOUND"));
    assertThat(appendDetail.lastSearchResult().path("data").path("groups"))
        .singleElement()
        .satisfies(
            group -> {
              assertThat(group.path("cabins")).hasSize(2);
              assertThat(group.path("cabins").get(0).path("id").asText())
                  .isEqualTo(alternativeCabinId.toString());
              assertThat(group.path("cabins").get(1).path("id").asText())
                  .isEqualTo(appendedCabinId.toString());
            });

    AssistantMessage replaceTurn =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Замени на БК-2"));
    completeSearch(
        conversationId,
        replaceTurn.getId(),
        "replace",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","resultMode":"REPLACE","notices":[],"data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-2","quantity":1},"cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, replacementCabinId)));

    tools.jackson.databind.JsonNode replacement =
        service.detail(owner, conversationId).lastSearchResult();
    assertThat(replacement.path("resultMode").asText()).isEqualTo("REPLACE");
    assertThat(replacement.path("data").path("groups"))
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.path("cabins").get(0).path("id").asText())
                    .isEqualTo(replacementCabinId.toString()));
  }

  @Test
  void detailKeepsOtherwiseEqualSearchGroupsWithDifferentCategoriesSeparate() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID freeCabinId = UUID.randomUUID();
    UUID newCabinId = UUID.randomUUID();
    UUID orCategoriesCabinId = UUID.randomUUID();
    UUID allCategoriesCabinId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    AssistantMessage user =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Покажи БК-1"));
    ObjectMapper objectMapper = new ObjectMapper();

    completeSearch(
        conversationId,
        user.getId(),
        "ordinary",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"category":"Обычная","quantity":1},
               "cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, freeCabinId)));
    completeSearch(
        conversationId,
        user.getId(),
        "new-category",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"category":"Новая","quantity":1},
               "cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, newCabinId)));
    completeSearch(
        conversationId,
        user.getId(),
        "ordinary-or-itr",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"categories":["Обычная","ИТР"],"quantity":1},
               "cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, orCategoriesCabinId)));
    completeSearch(
        conversationId,
        user.getId(),
        "all-categories",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"quantity":1},
               "cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, allCategoriesCabinId)));

    tools.jackson.databind.JsonNode groups =
        service(new CountingLogisticsClient(inquiryId, UUID.randomUUID()))
            .detail(owner, conversationId)
            .lastSearchResult()
            .path("data")
            .path("groups");

    assertThat(groups.size()).isEqualTo(4);
    assertThat(groups.get(0).path("group").path("category").asText()).isEqualTo("Обычная");
    assertThat(groups.get(0).path("cabins").get(0).path("status").asText()).isEqualTo("FREE");
    assertThat(groups.get(1).path("group").path("category").asText()).isEqualTo("Новая");
    assertThat(groups.get(1).path("cabins").get(0).path("status").asText()).isEqualTo("FREE");
    assertThat(groups.get(2).path("group").path("categories"))
        .extracting(tools.jackson.databind.JsonNode::asText)
        .containsExactly("Обычная", "ИТР");
    assertThat(groups.get(2).path("cabins").get(0).path("id").asText())
        .isEqualTo(orCategoriesCabinId.toString());
    assertThat(groups.get(3).path("group").has("categories")).isFalse();
    assertThat(groups.get(3).path("cabins").get(0).path("id").asText())
        .isEqualTo(allCategoriesCabinId.toString());
  }

  @Test
  void detailKeepsOtherwiseEqualSearchGroupsWithDifferentCharacteristicsOrLinoleumSeparate() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID characteristicCabinId = UUID.randomUUID();
    UUID linoleumCabinId = UUID.randomUUID();
    UUID noLinoleumCabinId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    AssistantMessage user =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Покажи БК-1"));
    ObjectMapper objectMapper = new ObjectMapper();

    completeSearch(
        conversationId,
        user.getId(),
        "characteristic",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"category":null,"characteristics":"с верандой","linoleum":true,"quantity":1},
               "cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, characteristicCabinId)));
    completeSearch(
        conversationId,
        user.getId(),
        "linoleum",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"category":null,"characteristics":"с мебелью","linoleum":true,"quantity":1},
               "cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, linoleumCabinId)));
    completeSearch(
        conversationId,
        user.getId(),
        "no-linoleum",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[
              {"group":{"cabinType":"БК-1","finish":null,"dimensions":null,"category":null,"characteristics":"с мебелью","linoleum":false,"quantity":1},
               "cabins":[{"id":"%s","status":"FREE"}]}
            ]}}
            """
                .formatted(warehouseId, noLinoleumCabinId)));

    tools.jackson.databind.JsonNode groups =
        service(new CountingLogisticsClient(inquiryId, UUID.randomUUID()))
            .detail(owner, conversationId)
            .lastSearchResult()
            .path("data")
            .path("groups");

    assertThat(groups.size()).isEqualTo(3);
    assertThat(groups.get(0).path("group").path("characteristics").asText())
        .isEqualTo("с верандой");
    assertThat(groups.get(1).path("group").path("linoleum").booleanValue()).isTrue();
    assertThat(groups.get(2).path("group").path("linoleum").booleanValue()).isFalse();
    assertThat(groups.get(0).path("cabins").get(0).path("id").asText())
        .isEqualTo(characteristicCabinId.toString());
    assertThat(groups.get(1).path("cabins").get(0).path("id").asText())
        .isEqualTo(linoleumCabinId.toString());
    assertThat(groups.get(2).path("cabins").get(0).path("id").asText())
        .isEqualTo(noLinoleumCabinId.toString());
  }

  @Test
  void detailDoesNotRestoreSearchCarouselWhenLastTurnExpiryIsMissingMalformedOrExpired() {
    UUID owner = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId, owner, UUID.randomUUID(), inquiryId, "PERSON", "Client summary"));
    ObjectMapper objectMapper = new ObjectMapper();

    AssistantMessage missingExpiryTurn =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Покажи свободные"));
    completeSearch(
        conversationId,
        missingExpiryTurn.getId(),
        "valid-before-missing",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[]}}
            """
                .formatted(warehouseId)));
    completeSearch(
        conversationId,
        missingExpiryTurn.getId(),
        "missing-expiry",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","groups":[]}}
            """
                .formatted(warehouseId)));

    AssistantConversationService service =
        service(new CountingLogisticsClient(inquiryId, UUID.randomUUID()));
    assertThat(service.detail(owner, conversationId).lastSearchResult()).isNull();

    AssistantMessage malformedExpiryTurn =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Покажи ещё раз"));
    completeSearch(
        conversationId,
        malformedExpiryTurn.getId(),
        "valid-before-malformed",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2030-07-27T12:10:00Z","groups":[]}}
            """
                .formatted(warehouseId)));
    completeSearch(
        conversationId,
        malformedExpiryTurn.getId(),
        "malformed-expiry",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"not-a-date-time","groups":[]}}
            """
                .formatted(warehouseId)));

    assertThat(service.detail(owner, conversationId).lastSearchResult()).isNull();

    AssistantMessage expiredTurn =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Покажи старую подборку"));
    completeSearch(
        conversationId,
        expiredTurn.getId(),
        "expired",
        objectMapper.readTree(
            """
            {"tool":"search_available_cabins","data":{"warehouseId":"%s","expiresAt":"2020-07-27T12:10:00Z","groups":[]}}
            """
                .formatted(warehouseId)));

    assertThat(service.detail(owner, conversationId).lastSearchResult()).isNull();
  }

  private void completeSearch(
      UUID conversationId,
      UUID turnMessageId,
      String providerCallId,
      tools.jackson.databind.JsonNode result) {
    AssistantToolCall call =
        toolCalls.saveAndFlush(
            AssistantToolCall.start(
                conversationId,
                turnMessageId,
                providerCallId,
                AssistantToolDefinitions.SEARCH_AVAILABLE_CABINS,
                new ObjectMapper().createObjectNode()));
    call.complete(result);
    toolCalls.saveAndFlush(call);
  }

  private static String bookingEnvelope(
      UUID eventId, UUID inquiryId, UUID conversationId, UUID orderId) {
    return """
    {
      "envelopeVersion":2,
      "eventId":"%s",
      "eventType":"logistics.rental-inquiry.booked.v1",
      "eventVersion":1,
      "occurredAt":"2026-07-27T12:00:00Z",
      "recordedAt":"2026-07-27T12:00:01Z",
      "producer":"logistics-service",
      "aggregateType":"RENTAL_INQUIRY",
      "aggregateId":"%s",
      "aggregateVersion":1,
      "correlation":{"correlationId":"%s","causationId":null},
      "actorRef":null,
      "payload":{"conversationId":"%s","orderId":"%s"}
    }
    """
        .formatted(eventId, inquiryId, conversationId, conversationId, orderId);
  }

  private AssistantConversationService service(LogisticsClient logistics) {
    ObjectMapper objectMapper = new ObjectMapper();
    return new AssistantConversationService(
        conversations,
        creationStore,
        messages,
        toolCalls,
        logistics,
        new AssistantResponseMapperImpl(),
        new AssistantClarificationService(clarificationQuestions),
        new AssistantSelectionService(logistics, objectMapper));
  }

  private static AssistantApiModels.CreateConversationResponse createAfter(
      CountDownLatch start,
      AssistantConversationService service,
      UUID owner,
      AssistantApiModels.CreateConversationRequest request) {
    try {
      start.await();
      return service.create(owner, request, "current-user-bearer");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Conversation creation test was interrupted", interrupted);
    }
  }

  private static Object answerAfter(
      CountDownLatch start,
      AssistantConversationService service,
      UUID owner,
      UUID conversationId,
      AssistantApiModels.TurnRequest answer) {
    try {
      start.await();
      return service.beginTurn(owner, conversationId, answer);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new IllegalStateException("Clarification answer test was interrupted", interrupted);
    } catch (RuntimeException conflict) {
      return conflict;
    }
  }

  /**
   * Thread-safe logistics fixture whose inquiry identity follows the caller idempotency key and
   * whose state can advance independently to reproduce Kafka event lag.
   */
  private static final class OrderLifecycleLogisticsClient implements LogisticsClient {
    private final UUID clientId;
    private final UUID warehouseId = UUID.randomUUID();
    private final Map<UUID, UUID> inquiryByConversation = new ConcurrentHashMap<>();
    private final Map<UUID, RentalInquiryContext> inquiries = new ConcurrentHashMap<>();
    private final List<Boolean> createTransactionStates = new CopyOnWriteArrayList<>();
    private final List<Boolean> readTransactionStates = new CopyOnWriteArrayList<>();

    private OrderLifecycleLogisticsClient(UUID clientId) {
      this.clientId = clientId;
    }

    private void changeState(UUID inquiryId, String state) {
      inquiries.compute(
          inquiryId,
          (ignored, current) -> {
            if (current == null) throw new IllegalArgumentException("Inquiry was not created");
            return new RentalInquiryContext(
                current.inquiryId(),
                current.clientId(),
                current.rentalOrderId(),
                current.warehouseId(),
                state);
          });
    }

    @Override
    public InquiryBootstrap createRentalInquiry(
        UUID conversationId,
        UUID requestedClientId,
        AssistantApiModels.NewClientRequest newClient,
        String bearerToken) {
      return createRentalInquiry(
          conversationId, requestedClientId, newClient, null, bearerToken);
    }

    @Override
    public InquiryBootstrap createRentalInquiry(
        UUID conversationId,
        UUID requestedClientId,
        AssistantApiModels.NewClientRequest newClient,
        UUID rentalOrderId,
        String bearerToken) {
      createTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
      UUID inquiryId =
          inquiryByConversation.computeIfAbsent(
              conversationId,
              value ->
                  UUID.nameUUIDFromBytes(
                      ("assistant-order-inquiry:" + value)
                          .getBytes(StandardCharsets.UTF_8)));
      inquiries.putIfAbsent(
          inquiryId,
          new RentalInquiryContext(inquiryId, clientId, rentalOrderId, warehouseId, "ACTIVE"));
      return new InquiryBootstrap(inquiryId, clientId, "ACTIVE", "PERSON", "Client summary");
    }

    @Override
    public RentalInquiryContext readRentalInquiryContext(
        UUID rentalInquiryId, String bearerToken) {
      readTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
      RentalInquiryContext inquiry = inquiries.get(rentalInquiryId);
      if (inquiry == null) {
        throw new dev.buhanzaz.rwms.assistant.service.AssistantUpstreamException(
            "Inquiry was not found by the test logistics boundary");
      }
      return inquiry;
    }

    @Override
    public tools.jackson.databind.JsonNode listAvailableCabinFacets(
        UUID rentalInquiryId, String bearerToken) {
      return new ObjectMapper().createObjectNode();
    }

    @Override
    public tools.jackson.databind.JsonNode searchAvailableCabins(
        UUID rentalInquiryId, UUID idempotencyKey, CabinSearch search, String bearerToken) {
      return new ObjectMapper().createObjectNode();
    }

    @Override
    public CabinSelection readCabinSelection(UUID rentalInquiryId, String bearerToken) {
      return new CabinSelection(rentalInquiryId, null, null, List.of(), List.of());
    }

    @Override
    public CabinSelection replaceCabinSelection(
        UUID rentalInquiryId,
        UUID idempotencyKey,
        UUID warehouseId,
        List<UUID> rentalItemIds,
        String bearerToken) {
      List<tools.jackson.databind.JsonNode> items =
          rentalItemIds.stream()
              .map(
                  id ->
                      (tools.jackson.databind.JsonNode)
                          new ObjectMapper()
                              .createObjectNode()
                              .put("id", id.toString())
                              .put("number", "CAB-1"))
              .toList();
      return new CabinSelection(
          rentalInquiryId,
          rentalItemIds.isEmpty() ? null : warehouseId,
          rentalItemIds.isEmpty() ? null : OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15),
          rentalItemIds,
          items);
    }

    @Override
    public tools.jackson.databind.JsonNode lookupCabinCatalog(
        UUID rentalInquiryId,
        UUID warehouseId,
        String query,
        int page,
        int size,
        String bearerToken) {
      return new ObjectMapper().createObjectNode();
    }

    @Override
    public Optional<ClientPresentation> findClientPresentation(
        UUID rentalInquiryId, String bearerToken) {
      return Optional.empty();
    }
  }

  /** Deterministic logistics fake that records creation calls and transaction state. */
  private static final class CountingLogisticsClient implements LogisticsClient {
    private final UUID inquiryId;
    private final UUID clientId;
    private final AtomicInteger creates = new AtomicInteger();
    private final List<UUID> requestedRentalOrderIds = new CopyOnWriteArrayList<>();
    private Optional<ClientPresentation> clientPresentation = Optional.empty();
    private final List<Boolean> createTransactionStates = new CopyOnWriteArrayList<>();
    private final UUID selectedWarehouseId = UUID.randomUUID();
    private final UUID selectedRentalItemId = UUID.randomUUID();

    private CountingLogisticsClient(UUID inquiryId, UUID clientId) {
      this.inquiryId = inquiryId;
      this.clientId = clientId;
    }

    @Override
    public InquiryBootstrap createRentalInquiry(
        UUID conversationId,
        UUID requestedClientId,
        AssistantApiModels.NewClientRequest newClient,
        String bearerToken) {
      createTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
      creates.incrementAndGet();
      return new InquiryBootstrap(inquiryId, clientId, "ACTIVE", "PERSON", "Client summary");
    }

    @Override
    public InquiryBootstrap createRentalInquiry(
        UUID conversationId,
        UUID requestedClientId,
        AssistantApiModels.NewClientRequest newClient,
        UUID rentalOrderId,
        String bearerToken) {
      requestedRentalOrderIds.add(rentalOrderId);
      return createRentalInquiry(conversationId, requestedClientId, newClient, bearerToken);
    }

    @Override
    public RentalInquiryContext readRentalInquiryContext(UUID rentalInquiryId, String bearerToken) {
      UUID rentalOrderId =
          requestedRentalOrderIds.isEmpty() ? null : requestedRentalOrderIds.getLast();
      return new RentalInquiryContext(
          inquiryId, clientId, rentalOrderId, selectedWarehouseId, "ACTIVE");
    }

    @Override
    public tools.jackson.databind.JsonNode listAvailableCabinFacets(
        UUID rentalInquiryId, String bearerToken) {
      return new ObjectMapper().createObjectNode();
    }

    @Override
    public tools.jackson.databind.JsonNode searchAvailableCabins(
        UUID rentalInquiryId, UUID idempotencyKey, CabinSearch search, String bearerToken) {
      return new ObjectMapper().createObjectNode();
    }

    @Override
    public CabinSelection readCabinSelection(UUID rentalInquiryId, String bearerToken) {
      return new CabinSelection(
          inquiryId,
          selectedWarehouseId,
          OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15),
          List.of(selectedRentalItemId),
          List.of(
              new ObjectMapper()
                  .createObjectNode()
                  .put("id", selectedRentalItemId.toString())
                  .put("number", "CAB-1")));
    }

    @Override
    public CabinSelection replaceCabinSelection(
        UUID rentalInquiryId,
        UUID idempotencyKey,
        UUID warehouseId,
        List<UUID> rentalItemIds,
        String bearerToken) {
      List<tools.jackson.databind.JsonNode> items =
          rentalItemIds.stream()
              .map(
                  id ->
                      (tools.jackson.databind.JsonNode)
                          new ObjectMapper()
                              .createObjectNode()
                              .put("id", id.toString())
                              .put("number", "CAB-1"))
              .toList();
      return new CabinSelection(
          inquiryId,
          warehouseId,
          rentalItemIds.isEmpty() ? null : OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15),
          rentalItemIds,
          items);
    }

    @Override
    public tools.jackson.databind.JsonNode lookupCabinCatalog(
        UUID rentalInquiryId,
        UUID warehouseId,
        String query,
        int page,
        int size,
        String bearerToken) {
      return new ObjectMapper().createObjectNode();
    }

    @Override
    public Optional<ClientPresentation> findClientPresentation(
        UUID rentalInquiryId, String bearerToken) {
      return clientPresentation;
    }
  }
}
