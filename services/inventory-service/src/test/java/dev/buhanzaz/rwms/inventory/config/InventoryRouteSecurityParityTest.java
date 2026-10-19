package dev.buhanzaz.rwms.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.util.ClassUtils;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that the complete inventory HTTP mapping and authentication surface remains identical to
 * its canonical OpenAPI contract.
 */
class InventoryRouteSecurityParityTest {
  private static final String API_PACKAGE = "dev.buhanzaz.rwms.inventory";
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "post", "put", "delete", "patch", "options", "head", "trace");
  private static final String CONCRETE_ID = "10000000-0000-0000-0000-000000000014";
  private static final Comparator<RouteKey> ROUTE_ORDER =
      Comparator.comparing(RouteKey::path).thenComparing(route -> route.method().name());

  @Test
  void canonicalMethodAndPathInventoryExactlyMatchesMergedControllerMappings() throws Exception {
    List<RouteKey> canonical = contractOperations().stream().map(ContractOperation::route).toList();
    List<RouteKey> implementation = controllerRoutes();

    assertThat(canonical).isNotEmpty().doesNotHaveDuplicates();
    assertThat(implementation).isNotEmpty().doesNotHaveDuplicates();
    assertThat(implementation)
        .as("merged Spring mappings must equal the canonical inventory operation inventory")
        .containsExactlyElementsOf(canonical);
  }

  @Test
  void canonicalSecurityInventoryMatchesTheRealOwnerFilterChain() throws Exception {
    try (AnnotationConfigWebApplicationContext context = securityContext()) {
      FilterChainProxy security = context.getBean(FilterChainProxy.class);
      for (ContractOperation operation : contractOperations()) {
        MockHttpServletRequest request =
            new MockHttpServletRequest(
                operation.route().method().name(), concretePath(operation.route().path()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean dispatched = new AtomicBoolean();
        security.doFilter(
            request,
            response,
            (ignoredRequest, terminalResponse) -> {
              dispatched.set(true);
              ((HttpServletResponse) terminalResponse).setStatus(204);
            });

        if (operation.security() == SecurityRequirement.BEARER_JWT) {
          assertThat(response.getStatus())
              .as("%s must reject an unauthenticated request", operation.route())
              .isEqualTo(401);
          assertThat(dispatched.get()).as("dispatch for %s", operation.route()).isFalse();
        } else {
          assertThat(dispatched.get())
              .as("%s must pass the security chain anonymously", operation.route())
              .isTrue();
        }
      }
    }
  }

  private static AnnotationConfigWebApplicationContext securityContext() {
    AnnotationConfigWebApplicationContext context =
        new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    TestPropertyValues.of(
            "rwms.cors.allowed-origins=https://panel.example",
            "rwms.inventory.security.dev-auth-bypass=false")
        .applyTo(context);
    context.register(SecurityConfiguration.class, SecurityTestBeans.class);
    context.refresh();
    return context;
  }

  private List<RouteKey> controllerRoutes() throws ClassNotFoundException {
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<RouteKey> routes = new ArrayList<>();
    for (var component : scanner.findCandidateComponents(API_PACKAGE)) {
      Class<?> controller =
          ClassUtils.forName(
              Objects.requireNonNull(component.getBeanClassName()), getClass().getClassLoader());
      RequestMapping controllerMapping =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping methodMapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) {
          continue;
        }
        assertThat(methodMapping.method())
            .as("HTTP method must be explicit for %s#%s", controller.getName(), method.getName())
            .isNotEmpty();
        for (String controllerPath : paths(controllerMapping)) {
          for (String methodPath : paths(methodMapping)) {
            for (RequestMethod requestMethod : methodMapping.method()) {
              routes.add(
                  new RouteKey(
                      HttpMethod.valueOf(requestMethod.name()),
                      normalizePath(joinPaths(controllerPath, methodPath))));
            }
          }
        }
      }
    }
    return routes.stream().sorted(ROUTE_ORDER).toList();
  }

  private List<ContractOperation> contractOperations() throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/inventory-service.yaml");
    Map<String, Object> document;
    try (InputStream input = Files.newInputStream(contract)) {
      document = map(new Yaml().load(input));
    }
    Object inheritedSecurity = document.get("security");
    List<ContractOperation> operations = new ArrayList<>();
    for (var pathEntry : map(document.get("paths")).entrySet()) {
      for (var methodEntry : map(pathEntry.getValue()).entrySet()) {
        String methodName = methodEntry.getKey().toLowerCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(methodName)) {
          continue;
        }
        Map<String, Object> operation = map(methodEntry.getValue());
        Object security = operation.getOrDefault("security", inheritedSecurity);
        operations.add(
            new ContractOperation(
                new RouteKey(
                    HttpMethod.valueOf(methodName.toUpperCase(Locale.ROOT)),
                    normalizePath(pathEntry.getKey())),
                securityRequirement(security, methodName + " " + pathEntry.getKey())));
      }
    }
    return operations.stream()
        .sorted(Comparator.comparing(ContractOperation::route, ROUTE_ORDER))
        .toList();
  }

  private static SecurityRequirement securityRequirement(Object value, String label) {
    assertThat(value).as("security for %s", label).isInstanceOf(List.class);
    List<?> requirements = (List<?>) value;
    if (requirements.isEmpty()) {
      return SecurityRequirement.ANONYMOUS;
    }
    assertThat(requirements).as("security for %s", label).hasSize(1);
    assertThat(requirements.getFirst()).as("security for %s", label).isInstanceOf(Map.class);
    Map<?, ?> requirement = (Map<?, ?>) requirements.getFirst();
    assertThat(requirement.size()).as("security scheme count for %s", label).isEqualTo(1);
    assertThat(requirement.containsKey("bearerJwt"))
        .as("bearer security scheme for %s", label)
        .isTrue();
    assertThat(requirement.get("bearerJwt"))
        .as("bearer scopes for %s", label)
        .isEqualTo(List.of());
    return SecurityRequirement.BEARER_JWT;
  }

  private static List<String> paths(RequestMapping mapping) {
    if (mapping == null) {
      return List.of("");
    }
    String[] declared = mapping.path().length == 0 ? mapping.value() : mapping.path();
    return declared.length == 0 ? List.of("") : Arrays.asList(declared);
  }

  private static String joinPaths(String controllerPath, String methodPath) {
    String left = stripTrailingSlash(controllerPath);
    String right = methodPath.startsWith("/") ? methodPath.substring(1) : methodPath;
    if (left.isEmpty()) {
      return right.startsWith("/") ? right : "/" + right;
    }
    if (right.isEmpty()) {
      return left;
    }
    return left + "/" + right;
  }

  private static String normalizePath(String value) {
    String[] segments = stripTrailingSlash(value).split("/", -1);
    for (int index = 0; index < segments.length; index++) {
      if (segments[index].matches("\\{[^/{}]+}")) {
        segments[index] = "{}";
      }
    }
    return String.join("/", segments);
  }

  private static String concretePath(String value) {
    String[] segments = value.split("/", -1);
    for (int index = 0; index < segments.length; index++) {
      if (segments[index].equals("{}")) {
        segments[index] = CONCRETE_ID;
      }
    }
    return String.join("/", segments);
  }

  private static String stripTrailingSlash(String value) {
    return value.length() > 1 && value.endsWith("/")
        ? value.substring(0, value.length() - 1)
        : value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  /** Exact normalized key used to compare one Spring mapping with one OpenAPI operation. */
  private record RouteKey(HttpMethod method, String path) {}

  /** One canonical operation together with its effective inherited or explicit security. */
  private record ContractOperation(RouteKey route, SecurityRequirement security) {}

  /** Supported canonical authentication classifications at this owner boundary. */
  private enum SecurityRequirement {
    BEARER_JWT,
    ANONYMOUS
  }

  /** Supplies only the infrastructure required to build the production security filter chain. */
  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  @Import(InventorySecurityProblemWriter.class)
  static class SecurityTestBeans {
    /** Re-exports the owner's CORS policy under the conventional standalone security bean name. */
    @Bean("corsConfigurationSource")
    CorsConfigurationSource securityCorsConfigurationSource(
        @Qualifier("inventoryCorsConfigurationSource") CorsConfigurationSource ownerCors) {
      return ownerCors;
    }

    @Bean
    CorrelationIdFilter correlationIdFilter() {
      return new CorrelationIdFilter();
    }

    @Bean
    JwtDecoder jwtDecoder() {
      return token ->
          Jwt.withTokenValue(token)
              .header("alg", "none")
              .subject("route-parity-probe")
              .issuedAt(Instant.EPOCH)
              .expiresAt(Instant.ofEpochSecond(Long.MAX_VALUE / 1_000_000_000L))
              .build();
    }

    @Bean
    ObjectMapper objectMapper() {
      return JsonMapper.builder().findAndAddModules().build();
    }
  }
}
