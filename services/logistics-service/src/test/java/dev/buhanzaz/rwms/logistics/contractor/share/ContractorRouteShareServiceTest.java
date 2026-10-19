package dev.buhanzaz.rwms.logistics.contractor.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.ApplyContractorRouteTaskActionRequest;
import dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.ContractorRouteShareResponse;
import dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.CreateContractorRouteShareRequest;
import dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.PublicContractorRouteShareResponse;
import dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.RevokeContractorRouteShareRequest;
import dev.buhanzaz.rwms.logistics.contractor.share.domain.ContractorRouteShare;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.driver.service.DriverTaskWorkerContentCodec;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.security.LogisticsAuthorizer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies exact create replay, live assignment fencing, public enrichment and action proxying. */
class ContractorRouteShareServiceTest {
  private static final UUID WAREHOUSE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID WORKER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID SUBJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID EXTERNAL_TASK_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID DRIVER_TASK_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000005");
  private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000006");
  private static final UUID ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000007");
  private static final UUID BOARD_TASK_ID = UUID.fromString("10000000-0000-0000-0000-000000000008");
  private static final UUID ENTRY_ID = UUID.fromString("10000000-0000-0000-0000-000000000009");
  private static final UUID EVIDENCE_ID = UUID.fromString("10000000-0000-0000-0000-000000000010");
  private static final UUID MEDIA_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
  private static final UUID WORK_ID = UUID.fromString("10000000-0000-0000-0000-000000000012");
  private static final UUID MATERIAL_ID = UUID.fromString("10000000-0000-0000-0000-000000000013");
  private static final UUID COMMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000014");
  private static final UUID SECOND_EXTERNAL_TASK_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000015");
  private static final UUID SECOND_DRIVER_TASK_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000016");
  private static final UUID SECOND_DOCUMENT_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000017");
  private static final UUID SECOND_ORDER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000018");
  private static final UUID SECOND_BOARD_TASK_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000019");
  private static final UUID SECOND_ENTRY_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000020");

  private final ContractorRouteShareStore store = mock(ContractorRouteShareStore.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final LogisticsAuthorizer access = mock(LogisticsAuthorizer.class);
  private final DriverLogisticsTaskRepository driverTasks =
      mock(DriverLogisticsTaskRepository.class);
  private final LogisticsDocumentRepository documents = mock(LogisticsDocumentRepository.class);
  private final RentalOrderRepository orders = mock(RentalOrderRepository.class);
  private final DriverTaskWorkerContentCodec workerContent =
      mock(DriverTaskWorkerContentCodec.class);
  private final ContractorRouteShareTokenService tokens =
      new ContractorRouteShareTokenService(
          new MockEnvironment(), "contractor-route-share-test-secret-32-characters");
  private final AtomicReference<ContractorRouteShare> persisted = new AtomicReference<>();

  private ContractorRouteShareService service;

  @BeforeEach
  void setUp() {
    service =
        new ContractorRouteShareService(
            store,
            tokens,
            dependencies,
            access,
            driverTasks,
            documents,
            orders,
            workerContent);
    when(access.subjectId(any())).thenReturn(SUBJECT_ID);
    when(store.findReplay(any(), any()))
        .thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
    when(store.insert(any()))
        .thenAnswer(
            invocation -> {
              ContractorRouteShare share = invocation.getArgument(0);
              ReflectionTestUtils.setField(share, "id", UUID.randomUUID());
              persisted.set(share);
              return share;
            });
    when(store.findById(any())).thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
    configureExactLocalAndRemoteBinding(WORKER_ID);
  }

  @Test
  void createsOneExactShareAndReplaysWithoutRepeatingRemoteValidation() {
    UUID key = UUID.randomUUID();
    CreateContractorRouteShareRequest request =
        request(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));

    ContractorRouteShareService.CreationResult created =
        service.create(null, WAREHOUSE_ID, key, request);
    ContractorRouteShareService.CreationResult replayed =
        service.create(null, WAREHOUSE_ID, key, request);

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThat(created.response().publicPath()).startsWith("/contractor-routes/");
    assertThat(created.response().externalTaskIds()).containsExactly(EXTERNAL_TASK_ID);
    assertThat(created.response().expiresAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(created.response().expiresAt().getNano() % 1_000_000).isZero();
    verify(dependencies, times(1)).readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID);
    verify(store, times(1)).insert(any());
  }

  @Test
  void rejectsUnauthorizedWarehouseBeforeCreateReplayOrRemoteValidation() {
    Jwt jwt = mock(Jwt.class);
    UUID key = UUID.randomUUID();
    CreateContractorRouteShareRequest request =
        request(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));
    doThrow(new AccessDeniedException("unauthorized warehouse"))
        .when(access)
        .requireEdit(jwt, WAREHOUSE_ID);

    assertThatThrownBy(() -> service.create(jwt, WAREHOUSE_ID, key, request))
        .isInstanceOf(AccessDeniedException.class);

    verify(access).requireEdit(jwt, WAREHOUSE_ID);
    verify(store, never()).findReplay(any(), any());
    verify(dependencies, never()).readContractorTaskExecution(any(), any());
    verify(store, never()).insert(any());
  }

  @Test
  void changedIdempotencyReuseConflictsBeforeAnySecondRemoteCall() {
    UUID key = UUID.randomUUID();
    OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
    service.create(null, WAREHOUSE_ID, key, request(expiry));

    assertThatThrownBy(
            () -> service.create(null, WAREHOUSE_ID, key, request(expiry.plusMinutes(1))))
        .isInstanceOfSatisfying(
            ContractorRouteShareProblem.class,
            problem ->
                assertThat(problem.code()).isEqualTo("CONTRACTOR_ROUTE_SHARE_IDEMPOTENCY_REUSED"));
    verify(dependencies, times(1)).readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID);
  }

  @Test
  void publicReadCombinesOnlyLocalOrderFactsWithLiveExactStepContentAndEvidence() {
    ContractorRouteShareResponse created = create();

    PublicContractorRouteShareResponse response = service.publicRoute(token(created));

    assertThat(response.tasks()).hasSize(1);
    var task = response.tasks().getFirst();
    assertThat(task.externalTaskId()).isEqualTo(EXTERNAL_TASK_ID);
    assertThat(task.address()).isEqualTo("Великий Новгород, Большая Санкт-Петербургская, 1");
    assertThat(task.contactPhone()).isEqualTo("+7 921 000-00-00");
    assertThat(task.cargo()).extracting(item -> item.kind()).containsExactly("WORK", "MATERIAL");
    assertThat(task.route()).hasSize(1);
    var entry = task.route().getFirst();
    assertThat(entry.works()).extracting(work -> work.id()).containsExactly(WORK_ID);
    assertThat(entry.materials())
        .extracting(material -> material.id())
        .containsExactly(MATERIAL_ID);
    assertThat(entry.comments()).extracting(comment -> comment.id()).containsExactly(COMMENT_ID);
    assertThat(entry.sourceMedia()).extracting(media -> media.mediaId()).containsExactly(MEDIA_ID);
    assertThat(entry.sourceMedia().getFirst().contentPath())
        .startsWith("/api/logistics/public/v1/contractor-route-shares/")
        .contains("/entries/" + ENTRY_ID + "/media/" + MEDIA_ID)
        .endsWith("/variants/LARGE/content");
    assertThat(entry.sourceMedia().getFirst().thumbnailPath()).endsWith("/variants/SMALL/content");
    assertThat(entry.evidence())
        .extracting(evidence -> evidence.evidenceId())
        .containsExactly(EVIDENCE_ID);
    assertThat(entry.evidence().getFirst().state()).isEqualTo("READY");
    assertThat(entry.evidence().getFirst().contentPath()).endsWith("/variants/LARGE/content");
    assertThat(task.sourceMedia().getFirst().contentPath())
        .contains("/entries/" + ENTRY_ID + "/media/" + MEDIA_ID);
  }

  @Test
  void reassignedRemoteTaskCollapsesToTheSameNotFoundProblem() {
    ContractorRouteShareResponse created = create();
    when(dependencies.readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID))
        .thenReturn(execution(UUID.randomUUID(), 3, "IN_PROGRESS"));

    assertThatThrownBy(() -> service.publicRoute(token(created)))
        .isInstanceOfSatisfying(
            ContractorRouteShareProblem.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(404);
              assertThat(problem.code()).isEqualTo("CONTRACTOR_ROUTE_SHARE_NOT_FOUND");
            });
    assertSameNotFound(
        () -> service.media(token(created), EXTERNAL_TASK_ID, ENTRY_ID, MEDIA_ID, 2, "SMALL"));
  }

  @Test
  void invalidExpiredAndRevokedCapabilitiesCollapseToTheSameNotFoundProblem() {
    ContractorRouteShareResponse created = create();
    String signedToken = token(created);

    assertSameNotFound(() -> service.publicRoute(signedToken + "0"));
    ReflectionTestUtils.setField(
        persisted.get(), "expiresAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
    assertSameNotFound(() -> service.publicRoute(signedToken));
    ReflectionTestUtils.setField(
        persisted.get(), "expiresAt", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
    persisted.get().revoke(OffsetDateTime.now(ZoneOffset.UTC));
    assertSameNotFound(() -> service.publicRoute(signedToken));
  }

  @Test
  void completeUsesExactReadyEvidenceVersionFenceAndIdempotencyKey() {
    ContractorRouteShareResponse created = create();
    UUID key = UUID.randomUUID();
    LogisticsDependencyGateway.ContractorTaskExecution refreshed = execution(WORKER_ID, 4, "DONE");
    when(dependencies.applyContractorTaskAction(
            WORKER_ID, EXTERNAL_TASK_ID, ENTRY_ID, key, "COMPLETE", 3, EVIDENCE_ID))
        .thenReturn(new LogisticsDependencyGateway.ContractorTaskActionResult(4, refreshed));

    var response =
        service.applyAction(
            token(created),
            EXTERNAL_TASK_ID,
            ENTRY_ID,
            key,
            new ApplyContractorRouteTaskActionRequest("complete", 3, EVIDENCE_ID));

    assertThat(response.currentVersion()).isEqualTo(4);
    assertThat(response.task().route().getFirst().version()).isEqualTo(4);
    verify(dependencies)
        .applyContractorTaskAction(
            WORKER_ID, EXTERNAL_TASK_ID, ENTRY_ID, key, "COMPLETE", 3, EVIDENCE_ID);
  }

  @Test
  void completeRejectsMissingOrNonReadyEvidenceBeforeCallingTaskBoardAction() {
    ContractorRouteShareResponse created = create();

    assertThatThrownBy(
            () ->
                service.applyAction(
                    token(created),
                    EXTERNAL_TASK_ID,
                    ENTRY_ID,
                    UUID.randomUUID(),
                    new ApplyContractorRouteTaskActionRequest("COMPLETE", 3, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.applyAction(
                    token(created),
                    EXTERNAL_TASK_ID,
                    ENTRY_ID,
                    UUID.randomUUID(),
                    new ApplyContractorRouteTaskActionRequest("COMPLETE", 3, UUID.randomUUID())))
        .isInstanceOfSatisfying(
            ContractorRouteShareProblem.class,
            problem -> assertThat(problem.code()).isEqualTo("CONTRACTOR_ROUTE_EVIDENCE_NOT_READY"));
    verify(dependencies, never())
        .applyContractorTaskAction(any(), any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void startRequiresTheFirstUnfinishedEntryAcrossAllSharedTasks() {
    configureSecondExactLocalAndRemoteBinding();
    ContractorRouteShareResponse created =
        service
            .create(
                null,
                WAREHOUSE_ID,
                UUID.randomUUID(),
                new CreateContractorRouteShareRequest(
                    WORKER_ID,
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(2),
                    List.of(EXTERNAL_TASK_ID, SECOND_EXTERNAL_TASK_ID)))
            .response();
    UUID operationId = UUID.randomUUID();

    assertProblemCode(
        () ->
            service.applyAction(
                token(created),
                SECOND_EXTERNAL_TASK_ID,
                SECOND_ENTRY_ID,
                operationId,
                new ApplyContractorRouteTaskActionRequest("START", 3, null)),
        "CONTRACTOR_ROUTE_ACTION_ORDER_CONFLICT");
    verify(dependencies, never())
        .applyContractorTaskAction(any(), any(), any(), any(), any(), anyLong(), any());

    when(dependencies.readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID))
        .thenReturn(execution(WORKER_ID, 4, "DONE"));
    LogisticsDependencyGateway.ContractorTaskExecution started =
        execution(
            WORKER_ID,
            SECOND_EXTERNAL_TASK_ID,
            SECOND_BOARD_TASK_ID,
            SECOND_DRIVER_TASK_ID,
            SECOND_ENTRY_ID,
            4,
            "IN_PROGRESS");
    when(dependencies.applyContractorTaskAction(
            WORKER_ID, SECOND_EXTERNAL_TASK_ID, SECOND_ENTRY_ID, operationId, "START", 3, null))
        .thenReturn(new LogisticsDependencyGateway.ContractorTaskActionResult(4, started));

    var response =
        service.applyAction(
            token(created),
            SECOND_EXTERNAL_TASK_ID,
            SECOND_ENTRY_ID,
            operationId,
            new ApplyContractorRouteTaskActionRequest("START", 3, null));

    assertThat(response.currentVersion()).isEqualTo(4);
    assertThat(response.task().externalTaskId()).isEqualTo(SECOND_EXTERNAL_TASK_ID);
    verify(dependencies)
        .applyContractorTaskAction(
            WORKER_ID, SECOND_EXTERNAL_TASK_ID, SECOND_ENTRY_ID, operationId, "START", 3, null);
  }

  @Test
  void evidenceUploadReservesBeforeMediaAndExactRetryReturnsTheSameSafePaths() {
    ContractorRouteShareResponse created = create();
    String signedToken = token(created);
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-09-01T10:15:30Z");
    byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(bytes);
    var reservation =
        new LogisticsDependencyGateway.ContractorEvidenceReservation(
            EVIDENCE_ID,
            1,
            "RESERVED",
            ENTRY_ID,
            "TASK_BOARD_ENTRY",
            ENTRY_ID,
            WAREHOUSE_ID,
            EVIDENCE_ID,
            capturedAt,
            "image/jpeg",
            bytes.length,
            sha256);
    when(dependencies.reserveContractorTaskEvidence(
            WORKER_ID,
            EXTERNAL_TASK_ID,
            ENTRY_ID,
            EVIDENCE_ID,
            capturedAt,
            "image/jpeg",
            bytes.length,
            sha256))
        .thenReturn(reservation);
    when(dependencies.uploadContractorTaskEvidence(
            WAREHOUSE_ID, WORKER_ID, ENTRY_ID, EVIDENCE_ID, "image/jpeg", sha256, bytes))
        .thenReturn(
            new LogisticsDependencyGateway.ContractorEvidenceMediaReceipt(MEDIA_ID, 3, "READY"));

    var first =
        service.uploadEvidence(
            signedToken,
            EXTERNAL_TASK_ID,
            ENTRY_ID,
            EVIDENCE_ID,
            EVIDENCE_ID,
            capturedAt,
            "image/jpeg",
            sha256,
            bytes);
    var replay =
        service.uploadEvidence(
            signedToken,
            EXTERNAL_TASK_ID,
            ENTRY_ID,
            EVIDENCE_ID,
            EVIDENCE_ID,
            capturedAt,
            "image/jpeg",
            sha256,
            bytes);

    assertThat(replay).isEqualTo(first);
    assertThat(first.state()).isEqualTo("READY");
    assertThat(first.mediaGeneration()).isEqualTo(3);
    assertThat(first.contentPath())
        .contains(signedToken)
        .contains("/entries/" + ENTRY_ID + "/media/" + MEDIA_ID)
        .endsWith("/variants/LARGE/content");
    assertThat(first.thumbnailPath()).endsWith("/variants/SMALL/content");
    verify(dependencies, times(2))
        .reserveContractorTaskEvidence(
            WORKER_ID,
            EXTERNAL_TASK_ID,
            ENTRY_ID,
            EVIDENCE_ID,
            capturedAt,
            "image/jpeg",
            bytes.length,
            sha256);
    verify(dependencies, times(2))
        .uploadContractorTaskEvidence(
            WAREHOUSE_ID, WORKER_ID, ENTRY_ID, EVIDENCE_ID, "image/jpeg", sha256, bytes);
  }

  @Test
  void processingEvidenceMapsToUploadingWithoutInventingReadablePaths() {
    ContractorRouteShareResponse created = create();
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-09-01T10:15:30Z");
    byte[] bytes = "pending".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(bytes);
    when(dependencies.reserveContractorTaskEvidence(
            any(), any(), any(), any(), any(), any(), anyLong(), any()))
        .thenReturn(
            new LogisticsDependencyGateway.ContractorEvidenceReservation(
                EVIDENCE_ID,
                1,
                "UPLOADING",
                ENTRY_ID,
                "TASK_BOARD_ENTRY",
                ENTRY_ID,
                WAREHOUSE_ID,
                EVIDENCE_ID,
                capturedAt,
                "image/jpeg",
                bytes.length,
                sha256));
    when(dependencies.uploadContractorTaskEvidence(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new LogisticsDependencyGateway.ContractorEvidenceMediaReceipt(MEDIA_ID, 0, "PROCESSING"));

    var response =
        service.uploadEvidence(
            token(created),
            EXTERNAL_TASK_ID,
            ENTRY_ID,
            EVIDENCE_ID,
            EVIDENCE_ID,
            capturedAt,
            "image/jpeg",
            sha256,
            bytes);

    assertThat(response.state()).isEqualTo("UPLOADING");
    assertThat(response.mediaGeneration()).isNull();
    assertThat(response.contentPath()).isNull();
    assertThat(response.thumbnailPath()).isNull();
  }

  @Test
  void evidenceValidationRejectsWrongKeyTypeSizeOrDigestBeforeEitherRemoteEffect() {
    ContractorRouteShareResponse created = create();
    String signedToken = token(created);
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-09-01T10:15:30Z");
    byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(bytes);

    assertProblemCode(
        () ->
            service.uploadEvidence(
                signedToken,
                EXTERNAL_TASK_ID,
                ENTRY_ID,
                EVIDENCE_ID,
                UUID.randomUUID(),
                capturedAt,
                "image/jpeg",
                sha256,
                bytes),
        "CONTRACTOR_ROUTE_EVIDENCE_IDEMPOTENCY_MISMATCH");
    assertProblemCode(
        () ->
            service.uploadEvidence(
                signedToken,
                EXTERNAL_TASK_ID,
                ENTRY_ID,
                EVIDENCE_ID,
                EVIDENCE_ID,
                capturedAt,
                "image/png",
                sha256,
                bytes),
        "CONTRACTOR_ROUTE_EVIDENCE_MEDIA_UNSUPPORTED");
    assertProblemCode(
        () ->
            service.uploadEvidence(
                signedToken,
                EXTERNAL_TASK_ID,
                ENTRY_ID,
                EVIDENCE_ID,
                EVIDENCE_ID,
                capturedAt,
                "image/webp",
                sha256(new byte[1_048_577]),
                new byte[1_048_577]),
        "CONTRACTOR_ROUTE_EVIDENCE_SIZE_INVALID");
    assertProblemCode(
        () ->
            service.uploadEvidence(
                signedToken,
                EXTERNAL_TASK_ID,
                ENTRY_ID,
                EVIDENCE_ID,
                EVIDENCE_ID,
                capturedAt,
                "image/jpeg",
                "not-a-sha",
                bytes),
        "CONTRACTOR_ROUTE_EVIDENCE_SHA256_INVALID");
    assertProblemCode(
        () ->
            service.uploadEvidence(
                signedToken,
                EXTERNAL_TASK_ID,
                ENTRY_ID,
                EVIDENCE_ID,
                EVIDENCE_ID,
                capturedAt,
                "image/jpeg",
                "0".repeat(64),
                bytes),
        "CONTRACTOR_ROUTE_EVIDENCE_SHA256_MISMATCH");
    verify(dependencies, never())
        .reserveContractorTaskEvidence(any(), any(), any(), any(), any(), any(), anyLong(), any());
    verify(dependencies, never())
        .uploadContractorTaskEvidence(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void mediaReadRequiresExactTaskEntryMediaGenerationAndVariantMembership() {
    ContractorRouteShareResponse created = create();
    String signedToken = token(created);
    when(dependencies.readContractorTaskMedia(
            WAREHOUSE_ID, WORKER_ID, ENTRY_ID, MEDIA_ID, 2, "SMALL"))
        .thenReturn(new LogisticsDependencyGateway.MediaContent(new byte[] {1, 2}, "image/webp"));

    var content = service.media(signedToken, EXTERNAL_TASK_ID, ENTRY_ID, MEDIA_ID, 2, "SMALL");

    assertThat(content.bytes()).containsExactly(1, 2);
    verify(dependencies)
        .readContractorTaskMedia(WAREHOUSE_ID, WORKER_ID, ENTRY_ID, MEDIA_ID, 2, "SMALL");
    assertSameNotFound(
        () -> service.media(signedToken, UUID.randomUUID(), ENTRY_ID, MEDIA_ID, 2, "SMALL"));
    assertSameNotFound(
        () ->
            service.media(signedToken, EXTERNAL_TASK_ID, UUID.randomUUID(), MEDIA_ID, 2, "SMALL"));
    assertSameNotFound(
        () ->
            service.media(signedToken, EXTERNAL_TASK_ID, ENTRY_ID, UUID.randomUUID(), 2, "SMALL"));
    assertSameNotFound(
        () -> service.media(signedToken, EXTERNAL_TASK_ID, ENTRY_ID, MEDIA_ID, 99, "SMALL"));
    assertSameNotFound(
        () -> service.media(signedToken, EXTERNAL_TASK_ID, ENTRY_ID, MEDIA_ID, 2, "ORIGINAL"));
  }

  @Test
  void invalidCapabilityStopsEvidenceAndMediaBeforeAnyPrivateEffect() {
    ContractorRouteShareResponse created = create();
    String invalid = token(created) + "0";
    byte[] bytes = "evidence".getBytes(StandardCharsets.UTF_8);

    assertSameNotFound(
        () ->
            service.uploadEvidence(
                invalid,
                EXTERNAL_TASK_ID,
                ENTRY_ID,
                EVIDENCE_ID,
                EVIDENCE_ID,
                OffsetDateTime.parse("2026-09-01T10:15:30Z"),
                "image/jpeg",
                sha256(bytes),
                bytes));
    assertSameNotFound(
        () -> service.media(invalid, EXTERNAL_TASK_ID, ENTRY_ID, MEDIA_ID, 2, "SMALL"));
    ReflectionTestUtils.setField(
        persisted.get(), "expiresAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
    assertSameNotFound(
        () ->
            service.uploadEvidence(
                token(created),
                EXTERNAL_TASK_ID,
                ENTRY_ID,
                EVIDENCE_ID,
                EVIDENCE_ID,
                OffsetDateTime.parse("2026-09-01T10:15:30Z"),
                "image/jpeg",
                sha256(bytes),
                bytes));
    ReflectionTestUtils.setField(
        persisted.get(), "expiresAt", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
    persisted.get().revoke(OffsetDateTime.now(ZoneOffset.UTC));
    assertSameNotFound(
        () -> service.media(token(created), EXTERNAL_TASK_ID, ENTRY_ID, MEDIA_ID, 2, "SMALL"));
    verify(dependencies, never())
        .reserveContractorTaskEvidence(any(), any(), any(), any(), any(), any(), anyLong(), any());
    verify(dependencies, never())
        .uploadContractorTaskEvidence(any(), any(), any(), any(), any(), any(), any());
    verify(dependencies, never())
        .readContractorTaskMedia(any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void revokeReturnsNoPublicPathAndUsesExpectedVersionFence() {
    ContractorRouteShareResponse created = create();
    when(store.revoke(eq(persisted.get().getId()), eq(WAREHOUSE_ID), eq(0L), any()))
        .thenAnswer(
            invocation -> {
              persisted.get().revoke(invocation.getArgument(3));
              return persisted.get();
            });

    ContractorRouteShareResponse revoked =
        service.revoke(
            null, WAREHOUSE_ID, persisted.get().getId(), new RevokeContractorRouteShareRequest(0));

    assertThat(revoked.publicPath()).isNull();
    assertThat(revoked.revokedAt()).isNotNull();
    verify(store).revoke(persisted.get().getId(), WAREHOUSE_ID, 0, revoked.revokedAt());
  }

  @Test
  void rejectsUnauthorizedWarehouseBeforeRouteShareRevocation() {
    Jwt jwt = mock(Jwt.class);
    UUID shareId = UUID.randomUUID();
    doThrow(new AccessDeniedException("unauthorized warehouse"))
        .when(access)
        .requireEdit(jwt, WAREHOUSE_ID);

    assertThatThrownBy(
            () ->
                service.revoke(
                    jwt,
                    WAREHOUSE_ID,
                    shareId,
                    new RevokeContractorRouteShareRequest(0)))
        .isInstanceOf(AccessDeniedException.class);

    verify(access).requireEdit(jwt, WAREHOUSE_ID);
    verify(store, never()).revoke(any(), any(), anyLong(), any());
  }

  private ContractorRouteShareResponse create() {
    return service
        .create(
            null,
            WAREHOUSE_ID,
            UUID.randomUUID(),
            request(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2)))
        .response();
  }

  private static CreateContractorRouteShareRequest request(OffsetDateTime expiresAt) {
    return new CreateContractorRouteShareRequest(WORKER_ID, expiresAt, List.of(EXTERNAL_TASK_ID));
  }

  private static String token(ContractorRouteShareResponse response) {
    return response.publicPath().substring("/contractor-routes/".length());
  }

  private void configureExactLocalAndRemoteBinding(UUID remoteWorkerId) {
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    when(task.getId()).thenReturn(DRIVER_TASK_ID);
    when(task.getExternalTaskId()).thenReturn(EXTERNAL_TASK_ID);
    when(task.getSourceType()).thenReturn(DriverTaskSourceType.LOGISTICS_DOCUMENT);
    when(task.getSourceId()).thenReturn(DOCUMENT_ID);
    when(task.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(task.getDriverAudienceMode()).thenReturn(DriverTaskAudienceMode.ASSIGNED_DRIVER);
    when(task.getPlannedDriverWorkerId()).thenReturn(WORKER_ID);
    when(task.getWorkerContentJson()).thenReturn("{}");
    when(driverTasks.findByExternalTaskId(EXTERNAL_TASK_ID)).thenReturn(Optional.of(task));

    LogisticsDocument document = mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(DOCUMENT_ID);
    when(document.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(document.getDriverWorkerId()).thenReturn(WORKER_ID);
    when(document.getRentalOrderId()).thenReturn(ORDER_ID);
    when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(order.getDeliveryAddress()).thenReturn("Великий Новгород, Большая Санкт-Петербургская, 1");
    when(order.getLatitude()).thenReturn(new BigDecimal("58.5215"));
    when(order.getLongitude()).thenReturn(new BigDecimal("31.2755"));
    when(order.getContactPhone()).thenReturn("+7 921 000-00-00");
    when(order.getComment()).thenReturn("Позвонить за час");
    when(orders.findPlanningCandidateById(ORDER_ID)).thenReturn(Optional.of(order));

    DriverTaskWorkerContent.SourceMedia localMedia =
        new DriverTaskWorkerContent.SourceMedia(
            MEDIA_ID,
            2,
            "image/jpeg",
            OffsetDateTime.parse("2026-08-31T09:00:00Z"),
            OffsetDateTime.parse("2026-08-31T09:01:00Z"));
    when(workerContent.decode("{}"))
        .thenReturn(
            new DriverTaskWorkerContent(
                "Доставить бытовку",
                List.of(
                    new DriverTaskWorkerContent.Work(
                        WORK_ID,
                        "Выгрузить бытовку",
                        1,
                        "шт.",
                        30,
                        "Проверить номер",
                        List.of(MEDIA_ID))),
                List.of(
                    new DriverTaskWorkerContent.Material(MATERIAL_ID, "Бытовка БК-2", 1, "шт.")),
                List.of(
                    new DriverTaskWorkerContent.Comment(
                        COMMENT_ID,
                        "Въезд с торца",
                        "Логист",
                        OffsetDateTime.parse("2026-08-31T08:00:00Z"))),
                List.of(localMedia)));

    when(dependencies.readContractorTaskExecution(WORKER_ID, EXTERNAL_TASK_ID))
        .thenReturn(execution(remoteWorkerId, 3, "IN_PROGRESS"));
  }

  private void configureSecondExactLocalAndRemoteBinding() {
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    when(task.getId()).thenReturn(SECOND_DRIVER_TASK_ID);
    when(task.getExternalTaskId()).thenReturn(SECOND_EXTERNAL_TASK_ID);
    when(task.getSourceType()).thenReturn(DriverTaskSourceType.LOGISTICS_DOCUMENT);
    when(task.getSourceId()).thenReturn(SECOND_DOCUMENT_ID);
    when(task.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(task.getDriverAudienceMode()).thenReturn(DriverTaskAudienceMode.ASSIGNED_DRIVER);
    when(task.getPlannedDriverWorkerId()).thenReturn(WORKER_ID);
    when(task.getWorkerContentJson()).thenReturn("{}");
    when(driverTasks.findByExternalTaskId(SECOND_EXTERNAL_TASK_ID)).thenReturn(Optional.of(task));

    LogisticsDocument document = mock(LogisticsDocument.class);
    when(document.getId()).thenReturn(SECOND_DOCUMENT_ID);
    when(document.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(document.getDriverWorkerId()).thenReturn(WORKER_ID);
    when(document.getRentalOrderId()).thenReturn(SECOND_ORDER_ID);
    when(documents.findById(SECOND_DOCUMENT_ID)).thenReturn(Optional.of(document));

    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(SECOND_ORDER_ID);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    when(order.getDeliveryAddress()).thenReturn("Великий Новгород, Псковская, 2");
    when(orders.findPlanningCandidateById(SECOND_ORDER_ID)).thenReturn(Optional.of(order));

    when(dependencies.readContractorTaskExecution(WORKER_ID, SECOND_EXTERNAL_TASK_ID))
        .thenReturn(
            execution(
                WORKER_ID,
                SECOND_EXTERNAL_TASK_ID,
                SECOND_BOARD_TASK_ID,
                SECOND_DRIVER_TASK_ID,
                SECOND_ENTRY_ID,
                3,
                "WAITING"));
  }

  private static LogisticsDependencyGateway.ContractorTaskExecution execution(
      UUID workerId, long entryVersion, String entryStatus) {
    return execution(
        workerId,
        EXTERNAL_TASK_ID,
        BOARD_TASK_ID,
        DRIVER_TASK_ID,
        ENTRY_ID,
        entryVersion,
        entryStatus);
  }

  private static LogisticsDependencyGateway.ContractorTaskExecution execution(
      UUID workerId,
      UUID externalTaskId,
      UUID boardTaskId,
      UUID driverTaskId,
      UUID entryId,
      long entryVersion,
      String entryStatus) {
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-08-31T09:00:00Z");
    OffsetDateTime recordedAt = OffsetDateTime.parse("2026-08-31T09:01:00Z");
    LogisticsDependencyGateway.ContractorTaskRouteEntry entry =
        new LogisticsDependencyGateway.ContractorTaskRouteEntry(
            entryId,
            entryVersion,
            0,
            0,
            1,
            "Доставка",
            "Доставить бытовку",
            entryStatus,
            45,
            List.of(
                new LogisticsDependencyGateway.ContractorTaskWork(
                    WORK_ID,
                    "Выгрузить бытовку",
                    1,
                    "шт.",
                    30,
                    "Проверить номер",
                    List.of(MEDIA_ID))),
            List.of(
                new LogisticsDependencyGateway.ContractorTaskMaterial(
                    MATERIAL_ID, "Бытовка БК-2", 1, "шт.")),
            List.of(
                new LogisticsDependencyGateway.ContractorTaskComment(
                    COMMENT_ID, "Въезд с торца", "Логист", recordedAt)),
            List.of(
                new LogisticsDependencyGateway.ContractorTaskSourceMedia(
                    MEDIA_ID, 2, "image/jpeg", capturedAt, recordedAt)),
            1,
            List.of(
                new LogisticsDependencyGateway.ContractorTaskEvidence(
                    EVIDENCE_ID,
                    1,
                    capturedAt,
                    recordedAt,
                    "READY",
                    MEDIA_ID,
                    2L,
                    null,
                    "image/jpeg")),
            true);
    return new LogisticsDependencyGateway.ContractorTaskExecution(
        workerId,
        externalTaskId,
        boardTaskId,
        5,
        WAREHOUSE_ID,
        "Доставка БК-2",
        "Доставить бытовку клиенту",
        "БК-172",
        LocalDate.of(2026, 9, 1),
        OffsetDateTime.parse("2026-09-01T15:00:00Z"),
        2,
        "ACTIVE",
        new LogisticsDependencyGateway.ContractorTaskSource("LOGISTICS_DRIVER_TASK", driverTaskId),
        List.of(entry));
  }

  private static void assertSameNotFound(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ContractorRouteShareProblem.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(404);
              assertThat(problem.code()).isEqualTo("CONTRACTOR_ROUTE_SHARE_NOT_FOUND");
              assertThat(problem.getMessage())
                  .isEqualTo("Маршрут не найден или ссылка больше не действует");
            });
  }

  private static void assertProblemCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String expectedCode) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ContractorRouteShareProblem.class,
            problem -> assertThat(problem.code()).isEqualTo(expectedCode));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
