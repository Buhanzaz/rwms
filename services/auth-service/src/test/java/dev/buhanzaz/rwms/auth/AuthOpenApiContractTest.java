package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.auth.api.ActorDisplayResponse;
import dev.buhanzaz.rwms.auth.api.AdminUserController;
import dev.buhanzaz.rwms.auth.api.AdminUserResponse;
import dev.buhanzaz.rwms.auth.api.ApiExceptionHandler;
import dev.buhanzaz.rwms.auth.api.AuthEventingAdminController;
import dev.buhanzaz.rwms.auth.api.InternalWorkerCredentialController;
import dev.buhanzaz.rwms.auth.api.UserController;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialResponse;
import dev.buhanzaz.rwms.auth.api.WorkerCredentialResponse.WorkerCredentialStatus;
import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.eventing.AuthAggregateType;
import dev.buhanzaz.rwms.auth.eventing.AuthOutboxStore;
import dev.buhanzaz.rwms.auth.eventing.AuthReplayCoordinator;
import dev.buhanzaz.rwms.auth.eventing.AuthReplayVerifier;
import dev.buhanzaz.rwms.auth.eventing.AuthSanitizedDltStore;
import dev.buhanzaz.rwms.auth.eventing.AuthShadowReconciler;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import dev.buhanzaz.rwms.auth.service.WorkerCredentialService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** Checks canonical auth boundaries against controller mappings and serialized HTTP messages. */
class AuthOpenApiContractTest {
  static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String WAREHOUSE = "00000000-0000-0000-0000-000000000001";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchemaFactory SCHEMAS =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final ObjectNode CONTRACT = readContract();
  private final UserAdministrationService users = mock(UserAdministrationService.class);
  private final WorkerCredentialService workers = mock(WorkerCredentialService.class);
  private final AuthOutboxStore outbox = mock(AuthOutboxStore.class);
  private final AuthSanitizedDltStore deadLetters = mock(AuthSanitizedDltStore.class);
  private final AuthShadowReconciler reconciler = mock(AuthShadowReconciler.class);
  private final AuthReplayCoordinator replay = mock(AuthReplayCoordinator.class);
  private final AuthSubjectProfileStore profiles = mock(AuthSubjectProfileStore.class);
  private final UsernamePasswordAuthenticationToken operator =
      UsernamePasswordAuthenticationToken.authenticated("admin", "", List.of());
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AdminUserController(users),
                new UserController(users),
                new InternalWorkerCredentialController(workers),
                new AuthEventingAdminController(outbox, deadLetters, reconciler, replay, profiles))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    when(users.replaceAccesses(eq(ID), anyList(), anyInt(), any()))
        .thenReturn(
            new AdminUserResponse(
                ID,
                4,
                "viewer",
                null,
                null,
                null,
                null,
                true,
                UserGlobalRole.VIEWER,
                false,
                false,
                List.of()));
    doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Physical deletion is prohibited"))
        .when(users)
        .delete(eq(ID), any());
    when(users.actorDisplays(anyList(), any()))
        .thenReturn(
            List.of(
                new ActorDisplayResponse(
                    ID, PrincipalType.WORKER, null, "worker.contract", null, null, null)));
    var credential =
        new WorkerCredentialResponse(
            "worker-contract", WAREHOUSE, "worker.contract", WorkerCredentialStatus.ACTIVE);
    when(workers.configure(eq("worker-contract"), any())).thenReturn(credential);
    when(workers.status("worker-contract")).thenReturn(credential);
    when(outbox.requeue(ID, 4)).thenReturn(true);
    when(deadLetters.requeue(ID, 4)).thenReturn(true);
    when(profiles.findSubjectIdByUsername("admin")).thenReturn(Optional.of(ID));
    when(reconciler.reconcile(any(), eq(ID), anyLong(), anyString(), eq(ID)))
        .thenReturn(
            new AuthShadowReconciler.Result(AuthAggregateType.USER_AUTHORIZATION, ID, 4, ID, 1));
    when(replay.rebuild(eq(ID), eq(ID), anyString()))
        .thenReturn(new AuthReplayVerifier.ReplayParityResult(1, 4, "a".repeat(64)));
  }

  @Test
  void everyNativeAdministrationWorkerAndRecoveryMappingHasExactlyOneCanonicalOperation() {
    var handler =
        mvc.getDispatcherServlet()
            .getWebApplicationContext()
            .getBean(RequestMappingHandlerMapping.class);
    Set<String> actual = new HashSet<>();
    handler
        .getHandlerMethods()
        .keySet()
        .forEach(
            mapping ->
                mapping
                    .getMethodsCondition()
                    .getMethods()
                    .forEach(
                        method ->
                            mapping
                                .getPatternValues()
                                .forEach(path -> actual.add(method.name() + " " + path))));
    Set<String> documented = new HashSet<>();
    CONTRACT
        .path("paths")
        .fields()
        .forEachRemaining(
            path -> {
              if (path.getKey().equals("/api/auth/csrf")
                  || path.getKey().equals("/api/customer/v1/registrations")) return;
              path.getValue()
                  .fieldNames()
                  .forEachRemaining(
                      method -> {
                        if (Set.of("get", "post", "put", "delete", "patch").contains(method))
                          documented.add(method.toUpperCase(Locale.ROOT) + " " + path.getKey());
                      });
            });
    assertThat(documented).hasSize(19).containsExactlyInAnyOrderElementsOf(actual);
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("omittedOperations")
  void restoredOperationsValidateActualHttpRequestsAndResponses(
      String method, String template, String body, int expectedStatus) throws Exception {
    var request = request(HttpMethod.valueOf(method), concretePath(template)).principal(operator);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    if (template.endsWith("actor-displays")) request.queryParam("subjectId", ID.toString());
    mvc.perform(request)
        .andExpect(status().is(expectedStatus))
        .andExpect(matchesContract(template));
  }

  static Stream<Arguments> omittedOperations() {
    return Stream.of(
        Arguments.of(
            "PUT",
            "/api/admin/users/{id}/password",
            "{\"password\":\"test-password\",\"expectedVersion\":4}",
            204),
        Arguments.of(
            "PUT",
            "/api/admin/users/{id}/warehouse-accesses",
            "{\"expectedVersion\":4,\"accesses\":[]}",
            200),
        Arguments.of("DELETE", "/api/admin/users/{id}", null, 409),
        Arguments.of("GET", "/api/users/actor-displays", null, 200),
        Arguments.of(
            "PUT",
            "/api/internal/worker-credentials/{workerId}",
            "{\"warehouseId\":\"spb\",\"appLogin\":\"worker.contract\",\"password\":\"test-password\"}",
            200),
        Arguments.of("DELETE", "/api/internal/worker-credentials/{workerId}", null, 204),
        Arguments.of(
            "POST",
            "/api/internal/worker-credentials/{workerId}/reset",
            "{\"password\":\"test-password\"}",
            204),
        Arguments.of("POST", "/api/internal/worker-credentials/{workerId}/disable", null, 204),
        Arguments.of("POST", "/api/internal/worker-credentials/{workerId}/enable", null, 204),
        Arguments.of("GET", "/api/internal/worker-credentials/{workerId}/status", null, 200),
        Arguments.of(
            "POST",
            "/api/admin/eventing/outbox/{eventId}/requeue",
            "{\"expectedAttemptCount\":4}",
            204),
        Arguments.of(
            "POST",
            "/api/admin/eventing/sanitized-dlt/{dltId}/requeue",
            "{\"expectedAttemptCount\":4}",
            204),
        Arguments.of(
            "POST",
            "/api/admin/eventing/shadow/{aggregateType}/{aggregateId}/reconcile",
            "{\"expectedCheckpointVersion\":4,\"reason\":\"Contract recovery\"}",
            200),
        Arguments.of(
            "POST",
            "/api/admin/eventing/shadow/rebuild",
            "{\"operationId\":\"" + ID + "\",\"reason\":\"Contract replay\"}",
            200));
  }

  @Test
  void omittedCheckpointVersionIsRejectedByTheActualHttpRequest() throws Exception {
    String template = "/api/admin/eventing/shadow/{aggregateType}/{aggregateId}/reconcile";
    mvc.perform(
            post(concretePath(template))
                .principal(operator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Initial checkpoint recovery\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(matchesContract(template));
    verifyNoInteractions(reconciler);
    assertThat(
            validationErrors(
                "ReconcileAuthShadowRequest", "{\"reason\":\"Initial checkpoint recovery\"}"))
        .isNotEmpty();
  }

  @ParameterizedTest
  @MethodSource("invalidBodies")
  void canonicalSchemasRejectMalformedCommandsAndSecretBearingResponses(String schema, String body)
      throws Exception {
    assertThat(validationErrors(schema, body)).isNotEmpty();
  }

  static Stream<Arguments> invalidBodies() {
    return Stream.of(
        Arguments.of("AdminPasswordResetRequest", "{\"password\":\"test-password\"}"),
        Arguments.of("AdminPasswordResetRequest", "{\"expectedVersion\":4,\"password\":\"short\"}"),
        Arguments.of("WarehouseAccessesUpdateRequest", "{\"expectedVersion\":4}"),
        Arguments.of(
            "WorkerCredentialRequest",
            "{\"warehouseId\":\"spb\",\"appLogin\":\"  \",\"password\":\"test-password\"}"),
        Arguments.of("PasswordResetRequest", "{\"password\":\"" + "p".repeat(201) + "\"}"),
        Arguments.of("RequeueOutboxRequest", "{\"expectedAttemptCount\":0}"),
        Arguments.of(
            "ReconcileAuthShadowRequest",
            "{\"expectedCheckpointVersion\":-1,\"reason\":\"Recovery\"}"),
        Arguments.of(
            "ReplayAuthShadowRequest", "{\"operationId\":\"bad-uuid\",\"reason\":\"Recovery\"}"),
        Arguments.of(
            "AuthShadowReplayParity",
            "{\"aggregateCount\":1,\"versionSum\":4,\"canonicalChecksum\":\"invalid\"}"),
        Arguments.of(
            "WorkerCredential",
            "{\"workerId\":\"worker-contract\",\"warehouseId\":\""
                + WAREHOUSE
                + "\",\"appLogin\":\"worker.contract\",\"status\":\"ABSENT\"}"),
        Arguments.of(
            "WorkerCredential",
            "{\"workerId\":\"worker-contract\",\"warehouseId\":\""
                + WAREHOUSE
                + "\",\"appLogin\":\"worker.contract\",\"status\":\"ACTIVE\",\"password\":\"secret\"}"),
        Arguments.of(
            "ActorDisplay",
            "{\"subjectId\":\""
                + ID
                + "\",\"principalType\":\"WORKER\",\"globalRole\":null,\"username\":\"worker.contract\",\"firstName\":null,\"lastName\":null,\"email\":null,\"middleName\":null}"));
  }

  static String concretePath(String template) {
    return template
        .replace("{workerId}", "worker-contract")
        .replace("{aggregateType}", "USER_AUTHORIZATION")
        .replaceAll("\\{[^}]+}", ID.toString());
  }

  /** Validates bytes emitted by MVC and accepted command bytes against the canonical operation. */
  static ResultMatcher matchesContract(String template) {
    return result -> {
      String method = result.getRequest().getMethod().toLowerCase(Locale.ROOT);
      JsonNode operation = CONTRACT.path("paths").path(template).path(method);
      assertThat(operation.isMissingNode())
          .as("canonical operation %s %s", method, template)
          .isFalse();
      int status = result.getResponse().getStatus();
      byte[] requestBody = result.getRequest().getContentAsByteArray();
      if (status < 400 && requestBody != null && requestBody.length > 0) {
        assertValid(
            operation.path("requestBody").path("content").path("application/json").path("schema"),
            JSON.readTree(result.getRequest().getContentAsByteArray()));
      }
      assertHttpResponse(
          template,
          method,
          status,
          result.getResponse().getContentType(),
          result.getResponse().getContentAsString());
    };
  }

  /** Checks the same canonical response schema for MVC and the disposable embedded HTTP server. */
  static void assertHttpResponse(
      String template, String method, int status, String contentType, String body)
      throws Exception {
    JsonNode response =
        CONTRACT
            .path("paths")
            .path(template)
            .path(method)
            .path("responses")
            .path(Integer.toString(status));
    assertThat(response.isMissingNode()).as("documented HTTP status %s", status).isFalse();
    if (status == 204) assertThat(body).isEmpty();
    if (body.isEmpty()) return;
    if (response.has("$ref")) response = CONTRACT.at(response.path("$ref").asText().substring(1));
    assertValid(
        response.path("content").path(contentType.split(";", 2)[0]).path("schema"),
        JSON.readTree(body));
  }

  private static void assertValid(JsonNode schema, JsonNode value) {
    assertThat(schema.isMissingNode()).as("declared body schema").isFalse();
    assertThat(validator(schema).validate(value)).isEmpty();
  }

  private static Set<com.networknt.schema.ValidationMessage> validationErrors(
      String name, String body) throws Exception {
    return validator(JSON.createObjectNode().put("$ref", "#/components/schemas/" + name))
        .validate(JSON.readTree(body));
  }

  private static com.networknt.schema.JsonSchema validator(JsonNode schema) {
    ObjectNode root = JSON.createObjectNode();
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.set("components", CONTRACT.path("components"));
    root.setAll((ObjectNode) schema);
    return SCHEMAS.getSchema(
        root, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
  }

  private static ObjectNode readContract() {
    Path path = Path.of(System.getProperty("rwms.contracts.dir"), "openapi/auth-service.yaml");
    try (var input = Files.newInputStream(path)) {
      return JSON.valueToTree(new Yaml().<Map<String, Object>>load(input));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot load canonical auth contract", failure);
    }
  }
}
