package dev.buhanzaz.rwms.logistics.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.inquiry.service.ClientPresentationTokenService;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end owner test for immutable cabin photo snapshots, subject-bound replay, anonymous token
 * isolation and exact media-generation scoping against a real Flyway/PostgreSQL schema.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.rental-inquiry.outbox-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CabinPhotoPresentationIntegrationTest {
  private static final UUID SUBJECT =
      UUID.fromString("10000000-0000-4000-8000-000000000101");
  private static final UUID OTHER_SUBJECT =
      UUID.fromString("10000000-0000-4000-8000-000000000102");
  private static final UUID WAREHOUSE =
      UUID.fromString("10000000-0000-4000-8000-000000000201");
  private static final UUID OTHER_WAREHOUSE =
      UUID.fromString("10000000-0000-4000-8000-000000000202");
  private static final UUID CABIN =
      UUID.fromString("10000000-0000-4000-8000-000000000301");
  private static final UUID PHOTO =
      UUID.fromString("10000000-0000-4000-8000-000000000401");
  private static final long CABIN_VERSION = 7;

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired ClientPresentationTokenService clientPresentationTokens;
  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void resetState() {
    jdbc.execute("truncate table cabin_photo_presentation");
    reset(dependencies);
    when(dependencies.readCabinPhotoPresentationSnapshot(CABIN))
        .thenReturn(cabin(WAREHOUSE, CABIN_VERSION));
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(List.of(media(List.of("SMALL", "LARGE"))));
    when(dependencies.readCabinPresentationMedia(WAREHOUSE, CABIN, PHOTO, 3, "SMALL"))
        .thenReturn(
            new LogisticsDependencyGateway.MediaContent(
                "small-image".getBytes(StandardCharsets.UTF_8), "image/webp"));
    when(dependencies.readCabinPresentationMedia(WAREHOUSE, CABIN, PHOTO, 3, "LARGE"))
        .thenReturn(
            new LogisticsDependencyGateway.MediaContent(
                "large-image".getBytes(StandardCharsets.UTF_8), "image/webp"));
  }

  @Test
  void createsImmutableSnapshotAndExactReplayReturnsTheSameLinkWithoutDependencyReads()
      throws Exception {
    UUID key = UUID.randomUUID();

    MvcResult created =
        mockMvc
            .perform(create(key, WAREHOUSE, CABIN_VERSION))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotency-Replayed"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.cabinId").value(CABIN.toString()))
            .andExpect(jsonPath("$.cabinNumber").value("БК-007"))
            .andExpect(jsonPath("$.photoCount").value(1))
            .andExpect(jsonPath("$.publicPath").value(org.hamcrest.Matchers.startsWith("/photos/")))
            .andReturn();
    String firstBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);

    mockMvc
        .perform(create(key, WAREHOUSE, CABIN_VERSION))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(content().json(firstBody));

    assertThat(rowCount()).isOne();
    verify(dependencies, times(1)).readCabinPhotoPresentationSnapshot(CABIN);
    verify(dependencies, times(1)).readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN));
  }

  @Test
  void changedRequestReuseOfTheSameSubjectKeyConflictsBeforeDependencies() throws Exception {
    UUID key = UUID.randomUUID();
    mockMvc.perform(create(key, WAREHOUSE, CABIN_VERSION)).andExpect(status().isCreated());
    reset(dependencies);

    mockMvc
        .perform(create(key, WAREHOUSE, CABIN_VERSION + 1))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    assertThat(rowCount()).isOne();
    verify(dependencies, never()).readCabinPhotoPresentationSnapshot(any());
    verify(dependencies, never()).readCabinMediaSnapshots(any(), any());
  }

  @Test
  void theSamePublicKeyIsIndependentForDifferentAuthenticatedSubjects() throws Exception {
    UUID key = UUID.randomUUID();
    mockMvc.perform(create(key, WAREHOUSE, CABIN_VERSION)).andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/logistics/v1/cabins/{cabinId}/photo-presentations", CABIN)
                .with(user(OTHER_SUBJECT, "rwms.read rwms.write", "EDIT"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(WAREHOUSE, CABIN_VERSION)))
        .andExpect(status().isCreated());

    assertThat(rowCount()).isEqualTo(2);
    verify(dependencies, times(2)).readCabinPhotoPresentationSnapshot(CABIN);
  }

  @Test
  void concurrentExactCreatesConvergeToOneSnapshotAndOneReplay() throws Exception {
    UUID key = UUID.randomUUID();
    CountDownLatch bothRequestsReachedAsset = new CountDownLatch(2);
    when(dependencies.readCabinPhotoPresentationSnapshot(CABIN))
        .thenAnswer(
            ignored -> {
              bothRequestsReachedAsset.countDown();
              if (!bothRequestsReachedAsset.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent requests did not reach the asset fence");
              }
              return cabin(WAREHOUSE, CABIN_VERSION);
            });

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<MvcResult> first =
          executor.submit(() -> mockMvc.perform(create(key, WAREHOUSE, CABIN_VERSION)).andReturn());
      Future<MvcResult> second =
          executor.submit(() -> mockMvc.perform(create(key, WAREHOUSE, CABIN_VERSION)).andReturn());
      MvcResult left = first.get(20, TimeUnit.SECONDS);
      MvcResult right = second.get(20, TimeUnit.SECONDS);

      assertThat(List.of(left.getResponse().getStatus(), right.getResponse().getStatus()))
          .containsExactlyInAnyOrder(201, 200);
      assertThat(left.getResponse().getContentAsString(StandardCharsets.UTF_8))
          .isEqualTo(right.getResponse().getContentAsString(StandardCharsets.UTF_8));
      assertThat(
              java.util.Arrays.asList(
                  left.getResponse().getHeader("Idempotency-Replayed"),
                  right.getResponse().getHeader("Idempotency-Replayed")))
          .containsExactlyInAnyOrder(null, "true");
      assertThat(rowCount()).isOne();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void staleVersionAndWrongWarehouseAreRejectedWithoutPersistingSnapshots() throws Exception {
    when(dependencies.readCabinPhotoPresentationSnapshot(CABIN))
        .thenReturn(cabin(WAREHOUSE, CABIN_VERSION + 1));
    mockMvc
        .perform(create(UUID.randomUUID(), WAREHOUSE, CABIN_VERSION))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CABIN_VERSION_CONFLICT"));
    verify(dependencies, never()).readCabinMediaSnapshots(any(), any());

    reset(dependencies);
    when(dependencies.readCabinPhotoPresentationSnapshot(CABIN))
        .thenReturn(cabin(OTHER_WAREHOUSE, CABIN_VERSION));
    mockMvc
        .perform(create(UUID.randomUUID(), WAREHOUSE, CABIN_VERSION))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CABIN_WAREHOUSE_MISMATCH"));

    assertThat(rowCount()).isZero();
    verify(dependencies, never()).readCabinMediaSnapshots(any(), any());
  }

  @Test
  void emptyReadyPhotoSnapshotConflictsWithoutCreatingAPublicLink() throws Exception {
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(
            List.of(new LogisticsDependencyGateway.CabinMediaSnapshot(CABIN, List.of())));

    mockMvc
        .perform(create(UUID.randomUUID(), WAREHOUSE, CABIN_VERSION))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CABIN_PHOTOS_EMPTY"));

    assertThat(rowCount()).isZero();
  }

  @Test
  void invalidAndClientPresentationTokensAreIndistinguishableNotFound() throws Exception {
    mockMvc
        .perform(get("/api/logistics/public/v1/cabin-photo-presentations/{token}", "invalid"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CABIN_PHOTO_PRESENTATION_NOT_FOUND"));

    String clientToken = clientPresentationTokens.issue(UUID.randomUUID(), 1);
    mockMvc
        .perform(get("/api/logistics/public/v1/cabin-photo-presentations/{token}", clientToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CABIN_PHOTO_PRESENTATION_NOT_FOUND"));
  }

  @Test
  void publicMetadataExposesOnlyContractFieldsAndStreamsOnlyScopedMedia() throws Exception {
    MvcResult result =
        mockMvc
            .perform(create(UUID.randomUUID(), WAREHOUSE, CABIN_VERSION))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode created = json.readTree(result.getResponse().getContentAsByteArray());
    String token = created.get("publicPath").stringValue().substring("/photos/".length());

    mockMvc
        .perform(get("/api/logistics/public/v1/cabin-photo-presentations/{token}", token))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.id").value(created.get("id").stringValue()))
        .andExpect(jsonPath("$.cabinNumber").value("БК-007"))
        .andExpect(jsonPath("$.dimensions").value("2.4x6"))
        .andExpect(jsonPath("$.finishing").value("ДВП"))
        .andExpect(jsonPath("$.category").value("Обычная"))
        .andExpect(jsonPath("$.characteristics[0]").value("Пластиковое окно"))
        .andExpect(jsonPath("$.characteristics[1]").value("Электрика КК"))
        .andExpect(jsonPath("$.linoleum").value(true))
        .andExpect(jsonPath("$.warehouseId").doesNotExist())
        .andExpect(jsonPath("$.rentalItemVersion").doesNotExist())
        .andExpect(jsonPath("$.createdBySubjectId").doesNotExist())
        .andExpect(jsonPath("$.status").doesNotExist())
        .andExpect(jsonPath("$.rentalType").doesNotExist())
        .andExpect(jsonPath("$.passport").doesNotExist())
        .andExpect(jsonPath("$.photos[0].mediaId").value(PHOTO.toString()))
        .andExpect(jsonPath("$.photos[0].generation").value(3))
        .andExpect(jsonPath("$.photos[0].sortOrder").value(2))
        .andExpect(
            jsonPath("$.photos[0].thumbnailUrl")
                .value(org.hamcrest.Matchers.endsWith("/" + PHOTO + "/3/SMALL")))
        .andExpect(
            jsonPath("$.photos[0].contentUrl")
                .value(org.hamcrest.Matchers.endsWith("/" + PHOTO + "/3/LARGE")));

    mockMvc
        .perform(
            get(
                "/api/logistics/public/v1/cabin-photo-presentations/{token}/media/{mediaId}/{generation}/{variant}",
                token,
                PHOTO,
                3,
                "LARGE"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(content().contentType("image/webp"))
        .andExpect(content().bytes("large-image".getBytes(StandardCharsets.UTF_8)));
    verify(dependencies)
        .readCabinPresentationMedia(WAREHOUSE, CABIN, PHOTO, 3, "LARGE");

    mockMvc
        .perform(
            get(
                "/api/logistics/public/v1/cabin-photo-presentations/{token}/media/{mediaId}/{generation}/{variant}",
                token,
                UUID.randomUUID(),
                3,
                "LARGE"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get(
                "/api/logistics/public/v1/cabin-photo-presentations/{token}/media/{mediaId}/{generation}/{variant}",
                token,
                PHOTO,
                4,
                "LARGE"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get(
                "/api/logistics/public/v1/cabin-photo-presentations/{token}/media/{mediaId}/{generation}/{variant}",
                token,
                PHOTO,
                3,
                "ORIGINAL"))
        .andExpect(status().isNotFound());
    verify(dependencies, times(1))
        .readCabinPresentationMedia(WAREHOUSE, CABIN, PHOTO, 3, "LARGE");
    verify(dependencies, times(1)).readCabinPhotoPresentationSnapshot(CABIN);

    JsonNode storedMetadata =
        json.readTree(
            jdbc.queryForObject(
                "select metadata_snapshot_json::text from cabin_photo_presentation where id=?",
                String.class,
                UUID.fromString(created.get("id").stringValue())));
    assertThat(storedMetadata.propertyNames())
        .containsExactlyInAnyOrder(
            "dimensions", "finishing", "category", "characteristics", "linoleum");
  }

  @Test
  void presentationsCreatedBeforeMetadataSnapshotsRemainReadableWithEmptyMetadata()
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(create(UUID.randomUUID(), WAREHOUSE, CABIN_VERSION))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode created = json.readTree(result.getResponse().getContentAsByteArray());
    UUID presentationId = UUID.fromString(created.get("id").stringValue());
    String token = created.get("publicPath").stringValue().substring("/photos/".length());
    jdbc.update(
        "update cabin_photo_presentation set metadata_snapshot_json='{}'::jsonb where id=?",
        presentationId);

    mockMvc
        .perform(get("/api/logistics/public/v1/cabin-photo-presentations/{token}", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.finishing").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.category").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.characteristics").isEmpty())
        .andExpect(jsonPath("$.linoleum").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void smallIsTheImmutableContentFallbackWhenLargeWasUnavailableAtCreation() throws Exception {
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(List.of(media(List.of("SMALL"))));
    MvcResult result =
        mockMvc
            .perform(create(UUID.randomUUID(), WAREHOUSE, CABIN_VERSION))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode created = json.readTree(result.getResponse().getContentAsByteArray());
    String token = created.get("publicPath").stringValue().substring("/photos/".length());

    mockMvc
        .perform(get("/api/logistics/public/v1/cabin-photo-presentations/{token}", token))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.photos[0].contentUrl")
                .value(org.hamcrest.Matchers.endsWith("/" + PHOTO + "/3/SMALL")));
    mockMvc
        .perform(
            get(
                "/api/logistics/public/v1/cabin-photo-presentations/{token}/media/{mediaId}/{generation}/{variant}",
                token,
                PHOTO,
                3,
                "LARGE"))
        .andExpect(status().isNotFound());
    verify(dependencies, never())
        .readCabinPresentationMedia(WAREHOUSE, CABIN, PHOTO, 3, "LARGE");
  }

  @Test
  void warehouseEditAndWriteScopeAreBothRequiredBeforeDependencyReads() throws Exception {
    mockMvc
        .perform(
            post("/api/logistics/v1/cabins/{cabinId}/photo-presentations", CABIN)
                .with(user("rwms.read", "EDIT"))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(WAREHOUSE, CABIN_VERSION)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/logistics/v1/cabins/{cabinId}/photo-presentations", CABIN)
                .with(user("rwms.read rwms.write", "VIEW"))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(WAREHOUSE, CABIN_VERSION)))
        .andExpect(status().isForbidden());

    assertThat(rowCount()).isZero();
    verify(dependencies, never()).readCabinPhotoPresentationSnapshot(any());
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
      UUID key, UUID warehouseId, long expectedVersion) {
    return post("/api/logistics/v1/cabins/{cabinId}/photo-presentations", CABIN)
        .with(user("rwms.read rwms.write", "EDIT"))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody(warehouseId, expectedVersion));
  }

  private RequestPostProcessor user(String scopes, String level) {
    return user(SUBJECT, scopes, level);
  }

  private RequestPostProcessor user(UUID subjectId, String scopes, String level) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(subjectId.toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", "WAREHOUSE_MANAGER")
                    .claim("scope", scopes)
                    .claim(
                        "warehouse_access",
                        List.of(
                            Map.of(
                                "warehouseId",
                                WAREHOUSE.toString(),
                                "level",
                                level))));
  }

  private String requestBody(UUID warehouseId, long expectedVersion) {
    return """
        {"warehouseId":"%s","expectedRentalItemVersion":%d}
        """
        .formatted(warehouseId, expectedVersion);
  }

  private static LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot cabin(
      UUID warehouseId, long version) {
    return new LogisticsDependencyGateway.CabinPhotoPresentationAssetSnapshot(
        CABIN,
        version,
        warehouseId,
        "БК-007",
        "2.4x6",
        "ДВП",
        "Обычная",
        List.of("Пластиковое окно", "Электрика КК"),
        true);
  }

  private static LogisticsDependencyGateway.CabinMediaSnapshot media(List<String> variants) {
    return new LogisticsDependencyGateway.CabinMediaSnapshot(
        CABIN,
        List.of(
            new LogisticsDependencyGateway.CabinMediaPhoto(PHOTO, 3, 2, variants)));
  }

  private int rowCount() {
    return jdbc.queryForObject("select count(*) from cabin_photo_presentation", Integer.class);
  }
}
