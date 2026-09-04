package dev.buhanzaz.rwms.logistics.contractor.share;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.PublicContractorEvidenceUploadResponse;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Verifies binary ingress limits before public route controller argument binding. */
class ContractorEvidenceIngressFilterTest {
  private static final String TOKEN = "signed-route-token.signature";
  private static final UUID TASK_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTRY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID EVIDENCE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID MEDIA_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String PATH =
      "/api/logistics/public/v1/contractor-route-shares/"
          + TOKEN
          + "/tasks/"
          + TASK_ID
          + "/entries/"
          + ENTRY_ID
          + "/evidence/"
          + EVIDENCE_ID;
  private static final String SHA256 = "0".repeat(64);
  private static final String GATEWAY_DECLARED_LENGTH = "X-RWMS-Contractor-Evidence-Length";

  private ContractorRouteShareService routeShares;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    routeShares = mock(ContractorRouteShareService.class);
    var filter =
        new ContractorEvidenceIngressFilter(
            JsonMapper.builder().findAndAddModules().build(), new RwmsProblemDetailFactory());
    mvc =
        MockMvcBuilders.standaloneSetup(new PublicContractorRouteShareController(routeShares))
            .addFilters(filter)
            .build();
  }

  @Test
  void validBoundedEvidenceReachesTheControllerAndApplicationService() throws Exception {
    byte[] body = "evidence".getBytes(StandardCharsets.UTF_8);
    stubSuccessfulUpload();

    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_LENGTH, body.length)
                .header("Idempotency-Key", EVIDENCE_ID)
                .header("X-Content-SHA256", SHA256)
                .header("X-Captured-At", "2026-09-01T10:15:30Z")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.evidenceId").value(EVIDENCE_ID.toString()))
        .andExpect(jsonPath("$.state").value("UPLOADING"));

    verify(routeShares)
        .uploadEvidence(
            eq(TOKEN),
            eq(TASK_ID),
            eq(ENTRY_ID),
            eq(EVIDENCE_ID),
            eq(EVIDENCE_ID),
            any(),
            eq("image/jpeg"),
            eq(SHA256),
            any(byte[].class));
  }

  @Test
  void chunkedGatewayRelayWithCanonicalInternalLengthReachesTheRouteService() throws Exception {
    byte[] body = "relay".getBytes(StandardCharsets.UTF_8);
    stubSuccessfulUpload();

    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header(GATEWAY_DECLARED_LENGTH, body.length)
                .header("Idempotency-Key", EVIDENCE_ID)
                .header("X-Content-SHA256", SHA256)
                .header("X-Captured-At", "2026-09-01T10:15:30Z")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.evidenceId").value(EVIDENCE_ID.toString()));

    verify(routeShares)
        .uploadEvidence(
            eq(TOKEN),
            eq(TASK_ID),
            eq(ENTRY_ID),
            eq(EVIDENCE_ID),
            eq(EVIDENCE_ID),
            any(),
            eq("image/jpeg"),
            eq(SHA256),
            any(byte[].class));
  }

  @Test
  void missingOrChunkedLengthIsRejectedBeforeTheRouteService() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.IMAGE_JPEG))
        .andExpect(status().isLengthRequired())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.code").value("CONTRACTOR_ROUTE_EVIDENCE_LENGTH_REQUIRED"));
    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_LENGTH, 3)
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .content("two"))
        .andExpect(status().isLengthRequired())
        .andExpect(jsonPath("$.code").value("CONTRACTOR_ROUTE_EVIDENCE_LENGTH_REQUIRED"));

    verifyNoInteractions(routeShares);
  }

  @Test
  void spoofedDuplicateInvalidOrMismatchedInternalLengthIsRejected() throws Exception {
    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_JPEG)
                .header(GATEWAY_DECLARED_LENGTH, 3)
                .content("two"))
        .andExpect(status().isLengthRequired());
    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header(GATEWAY_DECLARED_LENGTH, "3", "3")
                .content("two"))
        .andExpect(status().isLengthRequired());
    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header(GATEWAY_DECLARED_LENGTH, "invalid")
                .content("two"))
        .andExpect(status().isLengthRequired());
    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header(HttpHeaders.CONTENT_LENGTH, 3)
                .header(GATEWAY_DECLARED_LENGTH, 4)
                .content("two"))
        .andExpect(status().isLengthRequired())
        .andExpect(jsonPath("$.code").value("CONTRACTOR_ROUTE_EVIDENCE_LENGTH_REQUIRED"));

    verifyNoInteractions(routeShares);
  }

  @Test
  void oversizedRepresentationIsRejectedBeforeTheRouteService() throws Exception {
    mvc.perform(
            post(PATH)
                .contentType(MediaType.parseMediaType("image/webp"))
                .header(HttpHeaders.CONTENT_LENGTH, 1_048_577)
                .content("x"))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("CONTRACTOR_ROUTE_EVIDENCE_SIZE_INVALID"));

    verifyNoInteractions(routeShares);
  }

  @Test
  void oversizedInternalGatewayLengthIsRejectedBeforeTheRouteService() throws Exception {
    mvc.perform(
            post(PATH)
                .contentType(MediaType.parseMediaType("image/webp"))
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header(GATEWAY_DECLARED_LENGTH, 1_048_577)
                .content("x"))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("CONTRACTOR_ROUTE_EVIDENCE_SIZE_INVALID"));

    verifyNoInteractions(routeShares);
  }

  @Test
  void missingOrUnsupportedContentTypeIsRejectedBeforeTheRouteService() throws Exception {
    mvc.perform(post(PATH).header(HttpHeaders.CONTENT_LENGTH, 1).content("x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("CONTRACTOR_ROUTE_EVIDENCE_MEDIA_UNSUPPORTED"));
    mvc.perform(
            post(PATH)
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_LENGTH, 1)
                .content("x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("CONTRACTOR_ROUTE_EVIDENCE_MEDIA_UNSUPPORTED"));

    verifyNoInteractions(routeShares);
  }

  @Test
  void adjacentPublicPathsAreNotInterceptedByTheEvidenceFilter() throws Exception {
    mvc.perform(post(PATH + "/extra").contentType(MediaType.IMAGE_PNG).content("x"))
        .andExpect(status().isNotFound());

    verify(routeShares, never())
        .uploadEvidence(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  private void stubSuccessfulUpload() {
    when(routeShares.uploadEvidence(
            eq(TOKEN),
            eq(TASK_ID),
            eq(ENTRY_ID),
            eq(EVIDENCE_ID),
            eq(EVIDENCE_ID),
            any(),
            eq("image/jpeg"),
            eq(SHA256),
            any(byte[].class)))
        .thenReturn(
            new PublicContractorEvidenceUploadResponse(
                EVIDENCE_ID, 1, "UPLOADING", MEDIA_ID, null, "image/jpeg", null, null));
  }
}
