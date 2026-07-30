package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.CommitHtmlImportRequest;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportCandidateKind;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportEquipmentMapping;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportMappingAction;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportMediaReplacement;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportRowDecision;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportStatusMapping;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.ReplaceHtmlImportMediaRequest;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.SkipHtmlImportMediaRequest;
import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.UpdateHtmlImportPlanRequest;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportRowAction;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImportState;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportBinding;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportClient;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportJob;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportSource;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlImportService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "rwms.asset.media-import.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
@Import(RentalItemHtmlImportServiceIntegrationTest.MediaClientConfiguration.class)
class RentalItemHtmlImportServiceIntegrationTest {
  private static final UUID SPB2_WAREHOUSE_ID =
      UUID.fromString("74ada344-5dce-4fdf-9d45-6c5b116bbaaa");
  private static final UUID OTHER_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String RAW_ONLY_MARKER = "RAW_HTML_MUST_NOT_BE_STORED_7A91";
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired RentalItemHtmlImportService imports;
  @Autowired JdbcTemplate jdbc;
  @Autowired StubMediaAssetImportClient media;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "http://issuer.invalid");
    registry.add(
        "rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void commitsOnlyToSpb2AndAdvancesTheSanitizedDurableMediaJob() {
    media.reset();
    UUID actorSubjectId = UUID.randomUUID();
    String number = "HTML-" + UUID.randomUUID();
    byte[] html = html(number).getBytes(StandardCharsets.UTF_8);

    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            html);

    assertThat(preview.state())
        .as("preview=%s", preview)
        .isEqualTo(RentalItemHtmlImportState.READY);
    assertThat(preview.rowCount()).isEqualTo(1);
    assertThat(preview.mediaLinkCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject(
            """
            select count(*)
            from rental_item_html_import
            where plan_json like ?
               or source_sha256 like ?
            """,
            Integer.class,
            "%" + RAW_ONLY_MARKER + "%",
            "%" + RAW_ONLY_MARKER + "%"))
        .isZero();
    assertThat(jdbc.queryForObject(
            """
            select count(*)
            from rental_item_html_import_row
            where parsed_json like ?
               or decision_json like ?
               or diagnostic_codes_json like ?
            """,
            Integer.class,
            "%https://disk.yandex.ru%",
            "%https://disk.yandex.ru%",
            "%https://disk.yandex.ru%"))
        .isZero();

    var committed =
        imports.commit(
            actorSubjectId,
            UUID.randomUUID(),
            preview.id(),
            new CommitHtmlImportRequest(preview.version()));

    assertThat(committed.state())
        .isEqualTo(RentalItemHtmlImportState.ASSETS_COMMITTED);
    assertThat(media.preflightSources.get())
        .singleElement()
        .satisfies(
            source ->
                assertThat(source.publicUrl())
                    .isEqualTo(
                        "https://disk.yandex.ru/d/AbCdEfGhIjKlMn"));
    assertThat(jdbc.queryForObject(
            """
            select count(*)
            from rental_item_html_import_row
            where import_id=?
              and has_photo_link
              and parsed_json like ?
            """,
            Integer.class,
            preview.id(),
            "%AbCdEfGhIjKlMn%"))
        .isZero();
    assertThat(jdbc.queryForObject(
            """
            select count(*) from rental_item
            where warehouse_id=? and display_canonical_number=?
            """,
            Integer.class,
            SPB2_WAREHOUSE_ID,
            number.toUpperCase(java.util.Locale.ROOT)))
        .isOne();
    assertThat(jdbc.queryForObject(
            """
            select count(*) from rental_item
            where warehouse_id=? and display_canonical_number=?
            """,
            Integer.class,
            OTHER_WAREHOUSE_ID,
            number.toUpperCase(java.util.Locale.ROOT)))
        .isZero();

    media.status.set(MediaAssetImportJob.Status.PREFLIGHT_READY);
    imports.synchronizeMedia(preview.id());

    assertThat(imports.get(preview.id()).state())
        .isEqualTo(RentalItemHtmlImportState.MEDIA_IMPORTING);
    UUID createdCabinId =
        jdbc.queryForObject(
            """
            select id from rental_item
            where warehouse_id=? and display_canonical_number=?
            """,
            UUID.class,
            SPB2_WAREHOUSE_ID,
            number.toUpperCase(java.util.Locale.ROOT));
    assertThat(media.activationBindings.get())
        .singleElement()
        .satisfies(
            binding -> {
              assertThat(binding.cabinId()).isEqualTo(createdCabinId);
              assertThat(binding.sourceRowId())
                  .isEqualTo(media.preflightSources.get().getFirst().sourceRowId());
            });

    media.status.set(MediaAssetImportJob.Status.COMPLETED);
    imports.synchronizeMedia(preview.id());

    assertThat(imports.get(preview.id()).state())
        .isEqualTo(RentalItemHtmlImportState.COMPLETED);
  }

  @Test
  void failedMediaPhaseCannotReopenTheCommittedAssetPlan() {
    media.reset();
    UUID actorSubjectId = UUID.randomUUID();
    String number = "HTML-" + UUID.randomUUID();
    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            html(number).getBytes(StandardCharsets.UTF_8));
    imports.commit(
        actorSubjectId,
        UUID.randomUUID(),
        preview.id(),
        new CommitHtmlImportRequest(preview.version()));

    media.status.set(MediaAssetImportJob.Status.FAILED);
    imports.synchronizeMedia(preview.id());
    var failed = imports.get(preview.id());

    assertThat(failed.state()).isEqualTo(RentalItemHtmlImportState.FAILED);
    assertThatThrownBy(
            () ->
                imports.updatePlan(
                    preview.id(),
                    new UpdateHtmlImportPlanRequest(
                        failed.version(),
                        failed.plan().catalogMappings(),
                        failed.plan().equipmentMappings(),
                        failed.plan().statusMappings(),
                        failed.plan().rows())))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("can no longer be edited");
    assertThat(jdbc.queryForObject(
            """
            select count(*) from rental_item
            where warehouse_id=? and display_canonical_number=?
            """,
            Integer.class,
            SPB2_WAREHOUSE_ID,
            number.toUpperCase(java.util.Locale.ROOT)))
        .isOne();
  }

  @Test
  void replacesOnlySelectedWriteOnlyMediaLinksAfterRejectedPreflight() {
    media.reset();
    UUID actorSubjectId = UUID.randomUUID();
    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            html("HTML-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    imports.commit(
        actorSubjectId,
        UUID.randomUUID(),
        preview.id(),
        new CommitHtmlImportRequest(preview.version()));

    media.status.set(MediaAssetImportJob.Status.FAILED);
    imports.synchronizeMedia(preview.id());
    var failed = imports.get(preview.id());
    UUID rowId = imports.rowPage(preview.id(), 0, 10).content().getFirst().id();

    var requeued =
        imports.replaceMedia(
            actorSubjectId,
            UUID.randomUUID(),
            preview.id(),
            new ReplaceHtmlImportMediaRequest(
                failed.version(),
                List.of(
                    new HtmlImportMediaReplacement(
                        rowId, "https://disk.yandex.ru/d/QrStUvWxYz0123"))));

    assertThat(requeued.state()).isEqualTo(RentalItemHtmlImportState.ASSETS_COMMITTED);
    assertThat(media.replacementSources.get())
        .containsExactly(
            new MediaAssetImportSource(
                rowId, "https://disk.yandex.ru/d/QrStUvWxYz0123"));
    assertThat(jdbc.queryForObject(
            "select count(*) from rental_item_html_import_row where parsed_json like ?",
            Integer.class,
            "%QrStUvWxYz0123%"))
        .isZero();
  }

  @Test
  void explicitlySkipsOnlyTheFailedMediaPhase() {
    media.reset();
    UUID actorSubjectId = UUID.randomUUID();
    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            html("HTML-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    imports.commit(
        actorSubjectId,
        UUID.randomUUID(),
        preview.id(),
        new CommitHtmlImportRequest(preview.version()));

    media.status.set(MediaAssetImportJob.Status.FAILED);
    imports.synchronizeMedia(preview.id());
    var failed = imports.get(preview.id());

    var completed =
        imports.skipMedia(
            actorSubjectId,
            UUID.randomUUID(),
            preview.id(),
            new SkipHtmlImportMediaRequest(failed.version()));

    assertThat(completed.state())
        .isEqualTo(RentalItemHtmlImportState.COMPLETED_WITH_WARNINGS);
    assertThat(completed.failureCode()).isNull();
  }

  @Test
  void cancelsAnUncommittedDraftAndCascadesItsNormalizedRows() {
    UUID actorSubjectId = UUID.randomUUID();
    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            html("HTML-" + UUID.randomUUID(), "Свободная", "")
                .getBytes(StandardCharsets.UTF_8));

    assertThat(jdbc.queryForObject(
            "select count(*) from rental_item_html_import_row where import_id=?",
            Integer.class,
            preview.id()))
        .isOne();

    imports.cancel(preview.id(), preview.version());

    assertThat(jdbc.queryForObject(
            "select count(*) from rental_item_html_import where id=?",
            Integer.class,
            preview.id()))
        .isZero();
    assertThat(jdbc.queryForObject(
            "select count(*) from rental_item_html_import_row where import_id=?",
            Integer.class,
            preview.id()))
        .isZero();
  }

  @Test
  void refusesToCancelAfterCabinProcessingHasStarted() {
    UUID actorSubjectId = UUID.randomUUID();
    String number = "HTML-" + UUID.randomUUID();
    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            html(number, "Свободная", "").getBytes(StandardCharsets.UTF_8));
    var completed =
        imports.commit(
            actorSubjectId,
            UUID.randomUUID(),
            preview.id(),
            new CommitHtmlImportRequest(preview.version()));

    assertThatThrownBy(() -> imports.cancel(completed.id(), completed.version()))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("can no longer be cancelled");
    assertThat(jdbc.queryForObject(
            """
            select count(*) from rental_item
            where warehouse_id=? and display_canonical_number=?
            """,
            Integer.class,
            SPB2_WAREHOUSE_ID,
            number.toUpperCase(java.util.Locale.ROOT)))
        .isOne();
  }

  @Test
  void automaticallyMapsUniqueWordMatchesAndKeepsTiesForReview() {
    String source =
        html("HTML-" + UUID.randomUUID(), "Свободная", "")
            .replace(
                "<td data-field=\"UF_FURNITURE_JSON\" data-value=\"\"></td>",
                """
                <td data-field="UF_FURNITURE_JSON"
                    data-value='[{"id":7,"qty":1},{"id":10,"qty":1}]'></td>
                """);

    var preview =
        imports.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            source.getBytes(StandardCharsets.UTF_8));

    var furniture =
        preview.sourceCandidates().stream()
            .filter(candidate -> candidate.kind() == HtmlImportCandidateKind.EQUIPMENT)
            .toList();
    assertThat(furniture)
        .filteredOn(candidate -> candidate.sourceValue().equals("Лавка"))
        .singleElement()
        .satisfies(candidate -> assertThat(candidate.suggestedTargetId()).isNotNull());
    assertThat(furniture)
        .filteredOn(candidate -> candidate.sourceValue().equals("Стол офисный"))
        .singleElement()
        .satisfies(candidate -> assertThat(candidate.suggestedTargetId()).isNull());
  }

  @Test
  void commitsWithoutOptionalCompositionAndCanSkipFurniture() {
    media.reset();
    UUID actorSubjectId = UUID.randomUUID();
    String number = "HTML-" + UUID.randomUUID();
    String source =
        html(number, "Свободная", "")
            .replace(
                "<td data-field=\"UF_TYPE_ID\" data-value=\"237\">БК-1</td>",
                "<td data-field=\"UF_TYPE_ID\" data-value=\"999\">Неизвестная конструкция</td>")
            .replace(
                "<td data-field=\"UF_GABARIT_ID\" data-value=\"7\">2.4x6</td>",
                "<td data-field=\"UF_GABARIT_ID\" data-value=\"\"></td>")
            .replace(
                "<td data-field=\"UF_CATEGORY_ID\" data-value=\"1\">Новая</td>",
                "<td data-field=\"UF_CATEGORY_ID\" data-value=\"\"></td>")
            .replace(
                "<td data-field=\"UF_OTDELKA_ID\" data-value=\"1\">ДВП</td>",
                "<td data-field=\"UF_OTDELKA_ID\" data-value=\"999\">Неизвестная отделка</td>")
            .replace(
                "<td data-field=\"UF_FURNITURE_JSON\" data-value=\"\"></td>",
                """
                <td data-field="UF_FURNITURE_JSON"
                    data-value='[{"id":7,"qty":2}]'></td>
                """);

    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            source.getBytes(StandardCharsets.UTF_8));

    assertThat(preview.state()).isEqualTo(RentalItemHtmlImportState.READY);
    assertThat(preview.sourceCandidates())
        .filteredOn(
            candidate ->
                candidate.kind() == HtmlImportCandidateKind.TYPE
                    && candidate.sourceValue().equals("Неизвестная конструкция"))
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.required()).isFalse();
              assertThat(candidate.suggestedTargetId()).isNull();
            });
    assertThat(imports.rowPage(preview.id(), 0, 1).content().getFirst().diagnostics())
        .extracting(diagnostic -> diagnostic.code())
        .doesNotContain("TYPE_REQUIRES_MAPPING", "DIMENSION_REQUIRES_MAPPING");

    var ready =
        imports.updatePlan(
            preview.id(),
            new UpdateHtmlImportPlanRequest(
                preview.version(),
                List.of(),
                List.of(
                    new HtmlImportEquipmentMapping(
                        "Лавка", HtmlImportMappingAction.IGNORE, null, null)),
                List.of(),
                List.of()));
    assertThat(ready.state()).isEqualTo(RentalItemHtmlImportState.READY);

    var completed =
        imports.commit(
            actorSubjectId,
            UUID.randomUUID(),
            preview.id(),
            new CommitHtmlImportRequest(ready.version()));

    assertThat(completed.state()).isEqualTo(RentalItemHtmlImportState.COMPLETED);
    Map<String, Object> composition =
        jdbc.queryForMap(
            """
            select item.id, item.cabin_type_id, item.cabin_dimension_id,
                   item.cabin_category_id, finishing_item.name as finishing_name
            from rental_item item
            left join cabin_catalog_item finishing_item on finishing_item.id=item.cabin_finishing_id
            where item.warehouse_id=? and item.display_canonical_number=?
            """,
            SPB2_WAREHOUSE_ID,
            number.toUpperCase(java.util.Locale.ROOT));
    assertThat(composition.get("cabin_type_id")).isNull();
    assertThat(composition.get("cabin_dimension_id")).isNull();
    assertThat(composition.get("cabin_category_id")).isNull();
    assertThat(composition.get("finishing_name")).isEqualTo("—");
    UUID rentalItemId = (UUID) composition.get("id");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from equipment_balance where rental_item_id=?",
                Integer.class,
                rentalItemId))
        .isZero();
  }

  @Test
  void mapsAnUnknownStatusWithoutDuplicatingRowDecisionsInThePlan() {
    media.reset();
    UUID actorSubjectId = UUID.randomUUID();
    String number = "HTML-" + UUID.randomUUID();
    String sourceStatus = "Нестандартный статус";
    var preview =
        imports.create(
            actorSubjectId,
            UUID.randomUUID(),
            SPB2_WAREHOUSE_ID,
            html(number, sourceStatus, "").getBytes(StandardCharsets.UTF_8));
    var row = imports.rowPage(preview.id(), 0, 10).content().getFirst();

    assertThat(preview.state()).isEqualTo(RentalItemHtmlImportState.REVIEW_REQUIRED);
    var ready =
        imports.updatePlan(
            preview.id(),
            new UpdateHtmlImportPlanRequest(
                preview.version(),
                List.of(),
                List.of(),
                List.of(new HtmlImportStatusMapping(sourceStatus, RentalItemStatus.FREE)),
                List.of(
                    new HtmlImportRowDecision(
                        row.sourceRowId(),
                        RentalItemHtmlImportRowAction.CREATE,
                        row.proposedNumber(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of()))));

    assertThat(ready.state()).isEqualTo(RentalItemHtmlImportState.READY);
    assertThat(jdbc.queryForObject(
            """
            select count(*) from rental_item_html_import
            where id=? and plan_json like '%"sourceRowId"%'
            """,
            Integer.class,
            preview.id()))
        .isZero();
    assertThat(jdbc.queryForObject(
            """
            select count(*) from rental_item_html_import_row
            where import_id=? and decision_json like '%"sourceRowId"%'
            """,
            Integer.class,
            preview.id()))
        .isOne();

    var completed =
        imports.commit(
            actorSubjectId,
            UUID.randomUUID(),
            preview.id(),
            new CommitHtmlImportRequest(ready.version()));

    assertThat(completed.state()).isEqualTo(RentalItemHtmlImportState.COMPLETED);
    assertThat(jdbc.queryForObject(
            """
            select status from rental_item
            where warehouse_id=? and display_canonical_number=?
            """,
            String.class,
            SPB2_WAREHOUSE_ID,
            number.toUpperCase(java.util.Locale.ROOT)))
        .isEqualTo(RentalItemStatus.FREE.name());
  }

  private static String html(String number) {
    return html(
        number,
        "Свободная",
        "https://disk.yandex.ru/d/AbCdEfGhIjKlMn");
  }

  private static String html(String number, String status, String photoUrl) {
    return """
        <html><body>
          <script>%s</script>
          <table id="city-bootcamps-table">
            <tr class="bootcamp-row" data-bc-id="19180">
              <td class="bc-number">%s</td>
              <td data-field="UF_TYPE_ID" data-value="237">БК-1</td>
              <td data-field="UF_GABARIT_ID" data-value="7">2.4x6</td>
              <td data-field="UF_OTDELKA_ID" data-value="1">ДВП</td>
              <td data-field="UF_CATEGORY_ID" data-value="1">Новая</td>
              <td data-field="UF_CHARS_IDS" data-value=""></td>
              <td data-field="UF_LINOLEUM" data-value="0">Нет</td>
              <td data-field="UF_STATE_STORAGE" data-value=""></td>
              <td data-field="UF_STATUS_ID" data-value="1">%s</td>
              <td data-field="UF_COMMENT" data-value=""></td>
              <td data-field="UF_PHOTO_URL" data-value="%s"></td>
              <td data-field="UF_FURNITURE_JSON" data-value=""></td>
              <td data-field="UF_SHIPMENT_DATE" data-value=""></td>
              <td data-field="UF_RECEIVED_FROM" data-value=""></td>
              <td data-field="UF_PRICE" data-value="0"></td>
            </tr>
          </table>
        </body></html>
        """
        .formatted(RAW_ONLY_MARKER, number, status, photoUrl);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MediaClientConfiguration {
    @Bean
    @Primary
    StubMediaAssetImportClient stubMediaAssetImportClient() {
      return new StubMediaAssetImportClient();
    }
  }

  static final class StubMediaAssetImportClient
      implements MediaAssetImportClient {
    private final UUID jobId = UUID.randomUUID();
    private final AtomicReference<MediaAssetImportJob.Status> status =
        new AtomicReference<>(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
    private final AtomicReference<List<MediaAssetImportSource>>
        preflightSources = new AtomicReference<>();
    private final AtomicReference<List<MediaAssetImportBinding>>
        activationBindings = new AtomicReference<>();
    private final AtomicReference<List<MediaAssetImportSource>>
        replacementSources = new AtomicReference<>();
    private UUID importId;
    private UUID warehouseId;

    private void reset() {
      status.set(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
      preflightSources.set(null);
      activationBindings.set(null);
      replacementSources.set(null);
      importId = null;
      warehouseId = null;
    }

    @Override
    public MediaAssetImportJob preflight(
        UUID assetImportId,
        UUID requestedWarehouseId,
        List<MediaAssetImportSource> sources,
        UUID idempotencyKey) {
      importId = assetImportId;
      warehouseId = requestedWarehouseId;
      preflightSources.set(List.copyOf(sources));
      return job(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
    }

    @Override
    public MediaAssetImportJob get(UUID requestedJobId) {
      assertThat(requestedJobId).isEqualTo(jobId);
      return job(status.get());
    }

    @Override
    public MediaAssetImportJob activate(
        UUID requestedJobId,
        List<MediaAssetImportBinding> bindings,
        UUID idempotencyKey) {
      assertThat(requestedJobId).isEqualTo(jobId);
      activationBindings.set(List.copyOf(bindings));
      status.set(MediaAssetImportJob.Status.ACTIVATION_PENDING);
      return job(MediaAssetImportJob.Status.ACTIVATION_PENDING);
    }

    @Override
    public MediaAssetImportJob retry(
        UUID requestedJobId, UUID idempotencyKey) {
      assertThat(requestedJobId).isEqualTo(jobId);
      status.set(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
      return job(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
    }

    @Override
    public MediaAssetImportJob replacePreflightSources(
        UUID requestedJobId,
        List<MediaAssetImportSource> sources,
        UUID idempotencyKey) {
      assertThat(requestedJobId).isEqualTo(jobId);
      replacementSources.set(List.copyOf(sources));
      status.set(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
      return job(MediaAssetImportJob.Status.PREFLIGHT_PENDING);
    }

    private MediaAssetImportJob job(MediaAssetImportJob.Status current) {
      List<MediaAssetImportJob.SourceResult> results =
          preflightSources.get() == null
              ? List.of()
              : preflightSources.get().stream()
                  .map(
                      source ->
                          new MediaAssetImportJob.SourceResult(
                              source.sourceRowId(),
                              0,
                              0,
                              current == MediaAssetImportJob.Status.COMPLETED
                                  ? 1
                                  : 0,
                              0,
                              0,
                              List.of(),
                              List.of()))
                  .toList();
      return new MediaAssetImportJob(
          jobId,
          importId,
          warehouseId,
          current,
          0,
          0,
          null,
          results);
    }
  }
}
