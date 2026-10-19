package dev.buhanzaz.rwms.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.buhanzaz.rwms.gateway.security.GatewaySecurityProblemWriter;
import dev.buhanzaz.rwms.gateway.web.GatewayUpstreamProblemHandler;
import dev.buhanzaz.rwms.gateway.web.GatewayUpstreamProblemWriter;
import dev.buhanzaz.rwms.platform.security.JwtAudienceValidatorFactory;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves that canonical public domain operations are routable through exactly the intended gateway
 * family, while canonical internal operations and reserved private aliases are never routed.
 */
class GatewayRouteSecurityParityTest {
  private static final String AUTH_OWNER = "auth-service";
  private static final String CAD_OWNER = "cad-service";
  private static final String MEDIA_OWNER = "media-service";
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "post", "put", "delete", "patch", "options", "head", "trace");
  private static final String CONCRETE_ID = "10000000-0000-0000-0000-000000000014";
  private static final String CAD_GUEST_AUTHORIZATION =
      "CadGuest "
          + CONCRETE_ID
          + ".20000000-0000-0000-0000-000000000014."
          + "A".repeat(43);
  private static final Comparator<RouteKey> ROUTE_ORDER =
      Comparator.comparing(RouteKey::path).thenComparing(route -> route.method().name());
  private static final Map<RouteKey, String> SPECIAL_ROUTES = specialRoutes();
  private static final List<RouteKey> RESERVED_ALIAS_PROBES =
      List.of(
          new RouteKey(HttpMethod.GET, "/api/task-board/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/warehouse/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/asset/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/maintenance/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/cad/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/cad/private/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/media/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/media/private/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/inventory/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/inventory/private/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/logistics/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/logistics/private/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/dossier/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/dossier/private/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/analytics/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/analytics/private/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/assistant/internal/v1/probe"),
          new RouteKey(HttpMethod.GET, "/api/assistant/private/v1/probe"),
          new RouteKey(HttpMethod.GET, "/auth/api/internal/v1/probe"));

  @Test
  void everyCanonicalPublicDomainOperationSelectsItsOwningRoute() throws Exception {
    ContractInventory inventory = contractInventory();
    List<RouteCandidate> routes = domainRoutes();
    List<RouteKey> expected =
        inventory.publicDomain().stream()
            .map(GatewayOperation::gatewayRoute)
            .sorted(ROUTE_ORDER)
            .toList();
    List<RouteKey> routed = new ArrayList<>();

    assertThat(expected).isNotEmpty().doesNotHaveDuplicates();
    for (GatewayOperation operation : inventory.publicDomain()) {
      List<RouteCandidate> matches = matchingRoutes(routes, operation.gatewayRoute());
      assertThat(matches)
          .as("gateway route for %s %s", operation.owner(), operation.gatewayRoute())
          .isNotEmpty();
      RouteCandidate selected = selectedRoute(matches);
      assertThat(selected.owner())
          .as("selected owner for %s", operation.gatewayRoute())
          .isEqualTo(operation.owner());
      routed.add(operation.gatewayRoute());
    }

    assertThat(routed.stream().sorted(ROUTE_ORDER).toList())
        .as("routed canonical public operation inventory")
        .containsExactlyElementsOf(expected);
  }

  @Test
  void dedicatedTransportRoutesWinForEveryCanonicalSpecialOperation() throws Exception {
    ContractInventory inventory = contractInventory();
    List<RouteCandidate> routes = domainRoutes();
    Map<RouteKey, GatewayOperation> operations = new LinkedHashMap<>();
    for (GatewayOperation operation : inventory.publicDomain()) {
      operations.put(operation.gatewayRoute(), operation);
    }

    assertThat(operations.keySet()).containsAll(SPECIAL_ROUTES.keySet());
    for (var special : SPECIAL_ROUTES.entrySet()) {
      RouteCandidate selected = selectedRoute(matchingRoutes(routes, special.getKey()));
      assertThat(selected.name())
          .as("dedicated route selected for %s", special.getKey())
          .isEqualTo(special.getValue());
    }
  }

  @Test
  void inventoryOutcomeRecalculateHasOneSafeDedicatedRoute() {
    List<RouteCandidate> routes = domainRoutes();
    RouteKey recalculation =
        new RouteKey(
            HttpMethod.POST,
            "/api/inventory/v1/sessions/{}/outcome/recalculate");

    assertThat(matchingRoutes(routes, recalculation))
        .singleElement()
        .extracting(RouteCandidate::name)
        .isEqualTo("inventoryOutcomeRecalculateRoute");
    assertThat(
            matchingRoutes(
                routes,
                new RouteKey(
                    HttpMethod.POST,
                    "/api/inventory/v1/sessions/inventory%2f1/outcome/recalculate")))
        .isEmpty();
    assertThat(
            matchingRoutes(
                routes,
                new RouteKey(
                    HttpMethod.POST,
                    "/api/inventory/private/v1/sessions/{}/outcome/recalculate")))
        .isEmpty();
  }

  @Test
  void cadV1HasOneSafeConfiguredRouteWithoutPrivateAliases() {
    List<RouteCandidate> routes = domainRoutes();
    RouteKey cadProject = new RouteKey(HttpMethod.GET, "/api/cad/v1/projects/{}");

    assertThat(matchingRoutes(routes, cadProject))
        .singleElement()
        .extracting(RouteCandidate::name)
        .isEqualTo("cadRoutes");
    assertThat(
            matchingRoutes(
                routes, new RouteKey(HttpMethod.GET, "/api/cad/v1/projects/cad%2fproject")))
        .isEmpty();
    assertThat(
            matchingRoutes(
                routes, new RouteKey(HttpMethod.GET, "/api/cad/internal/v1/projects/{}")))
        .isEmpty();
    assertThat(
            matchingRoutes(
                routes, new RouteKey(HttpMethod.GET, "/api/cad/private/v1/projects/{}")))
        .isEmpty();
  }

  @Test
  void mediaSourceAndVariantUploadsHaveOneSafeDedicatedRoute() {
    List<RouteCandidate> routes = domainRoutes();
    for (String path :
        List.of(
            "/api/media/v1/upload-sessions/{}/content",
            "/api/media/v1/upload-sessions/{}/variants/{}/content")) {
      assertThat(matchingRoutes(routes, new RouteKey(HttpMethod.PUT, path)))
          .singleElement()
          .extracting(RouteCandidate::name)
          .isEqualTo("mediaUploadContentRoute");
    }
    assertThat(
            matchingRoutes(
                routes,
                new RouteKey(
                    HttpMethod.PUT,
                    "/api/media/v1/upload-sessions/{}/variants/small%2flarge/content")))
        .isEmpty();
    assertThat(
            matchingRoutes(
                routes,
                new RouteKey(
                    HttpMethod.PUT,
                    "/api/media/private/v1/upload-sessions/{}/variants/SMALL/content")))
        .isEmpty();
  }

  @Test
  void canonicalInternalOperationsAndReservedAliasesHaveZeroGatewayRoutes() throws Exception {
    ContractInventory inventory = contractInventory();
    List<RouteCandidate> routes = publicRoutes();

    assertThat(inventory.internal()).isNotEmpty();
    for (GatewayOperation operation : inventory.internal()) {
      assertThat(matchingRoutes(routes, operation.canonicalRoute()))
          .as("canonical internal path must not route: %s", operation.canonicalRoute())
          .isEmpty();
      assertThat(matchingRoutes(routes, operation.gatewayRoute()))
          .as("internal public-prefix alias must not route: %s", operation.gatewayRoute())
          .isEmpty();
    }
    for (RouteKey reservedAlias : RESERVED_ALIAS_PROBES) {
      assertThat(matchingRoutes(routes, reservedAlias))
          .as("reserved private/internal alias must not route: %s", reservedAlias)
          .isEmpty();
    }
  }

  @Test
  void canonicalSecurityMatchesTheRealGatewayFilterChain() throws Exception {
    ContractInventory inventory = contractInventory();
    try (AnnotationConfigWebApplicationContext context = securityContext()) {
      FilterChainProxy security = context.getBean(FilterChainProxy.class);
      for (GatewayOperation operation : inventory.publicDomain()) {
        AtomicBoolean dispatched = new AtomicBoolean();
        int status = filter(security, operation.gatewayRoute(), false, dispatched);
        switch (operation.security()) {
          case BEARER_JWT -> {
            assertThat(status)
                .as("%s must reject an unauthenticated edge request", operation.gatewayRoute())
                .isEqualTo(401);
            assertThat(dispatched.get()).as("dispatch for %s", operation.gatewayRoute()).isFalse();
          }
          case BEARER_JWT_OR_CAD_GUEST -> {
            assertThat(status)
                .as("%s must reject a missing credential", operation.gatewayRoute())
                .isEqualTo(401);
            assertThat(dispatched.get()).as("dispatch for %s", operation.gatewayRoute()).isFalse();
            AtomicBoolean guestDispatched = new AtomicBoolean();
            assertThat(
                    filter(
                        security,
                        operation.gatewayRoute(),
                        CAD_GUEST_AUTHORIZATION,
                        guestDispatched))
                .as("%s must admit a shaped CAD guest credential", operation.gatewayRoute())
                .isEqualTo(204);
            assertThat(guestDispatched.get())
                .as("guest dispatch for %s", operation.gatewayRoute())
                .isTrue();
          }
          case ANONYMOUS ->
              assertThat(dispatched.get())
                  .as("%s must pass the edge security chain anonymously", operation.gatewayRoute())
                  .isTrue();
        }
      }

      for (GatewayOperation operation : inventory.internal()) {
        AtomicBoolean dispatched = new AtomicBoolean();
        int status = filter(security, operation.gatewayRoute(), true, dispatched);
        assertThat(status)
            .as("internal alias must be denied at the edge: %s", operation.gatewayRoute())
            .isEqualTo(403);
        assertThat(dispatched.get()).as("dispatch for %s", operation.gatewayRoute()).isFalse();
      }
      for (RouteKey reservedAlias : RESERVED_ALIAS_PROBES) {
        AtomicBoolean dispatched = new AtomicBoolean();
        int status = filter(security, reservedAlias, true, dispatched);
        assertThat(status)
            .as("private/internal alias must be denied at the edge: %s", reservedAlias)
            .isEqualTo(403);
        assertThat(dispatched.get()).as("dispatch for %s", reservedAlias).isFalse();
      }
    }
  }

  @Test
  void anonymousCatalogDoesNotOpenWritesOrNeighboringPrivateReads() throws Exception {
    String base = "/api/logistics/public/v1/catalog/warehouses";
    List<String> catalogPaths =
        List.of(
            base,
            base + "/" + CONCRETE_ID + "/facets",
            base + "/" + CONCRETE_ID + "/cabins",
            base + "/" + CONCRETE_ID + "/cabins/" + CONCRETE_ID + "/photos/" + CONCRETE_ID);
    List<RouteKey> forbidden = new ArrayList<>();
    for (String path : catalogPaths) {
      for (HttpMethod method :
          List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
        forbidden.add(new RouteKey(method, path));
      }
    }
    forbidden.add(new RouteKey(HttpMethod.GET, base + "/" + CONCRETE_ID + "/cart"));
    forbidden.add(new RouteKey(HttpMethod.GET, "/api/logistics/customer/v1/warehouses"));
    forbidden.add(new RouteKey(HttpMethod.GET, "/api/logistics/customer/v1/profile"));
    forbidden.add(new RouteKey(HttpMethod.GET, "/api/media/v1/assets/" + CONCRETE_ID));

    try (AnnotationConfigWebApplicationContext context = securityContext()) {
      FilterChainProxy security = context.getBean(FilterChainProxy.class);
      for (RouteKey route : forbidden) {
        AtomicBoolean dispatched = new AtomicBoolean();
        assertThat(filter(security, route, false, dispatched))
            .as("anonymous request %s", route)
            .isEqualTo(401);
        assertThat(dispatched.get()).as("dispatch for %s", route).isFalse();
      }
    }
  }

  private static AnnotationConfigWebApplicationContext securityContext() {
    AnnotationConfigWebApplicationContext context =
        new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(SecurityTestBeans.class, GatewaySecurityConfiguration.class);
    context.refresh();
    return context;
  }

  private static int filter(
      FilterChainProxy security,
      RouteKey route,
      boolean authenticated,
      AtomicBoolean dispatched)
      throws Exception {
    return filter(
        security,
        route,
        authenticated ? "Bearer route-parity-probe" : null,
        dispatched);
  }

  private static int filter(
      FilterChainProxy security,
      RouteKey route,
      String authorization,
      AtomicBoolean dispatched)
      throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest(route.method().name(), concretePath(route.path()));
    if (authorization != null) {
      request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    security.doFilter(
        request,
        response,
        (ignoredRequest, terminalResponse) -> {
          dispatched.set(true);
          ((HttpServletResponse) terminalResponse).setStatus(204);
        });
    return response.getStatus();
  }

  @Test
  void delegatedAuthAndServiceLocalHealthAreExplicitlyOutsideDomainInventory()
      throws Exception {
    ContractInventory inventory = contractInventory();
    GatewayRouteConfiguration configuration = new GatewayRouteConfiguration();
    GatewayUpstreamProblemHandler upstream = mock(GatewayUpstreamProblemHandler.class);
    RouteCandidate authRoute =
        candidate(
            AUTH_OWNER,
            "authRoutes",
            configuration.authRoutes(routeProperties(), upstream));
    List<RouteCandidate> domainRoutes = domainRoutes();

    assertThat(inventory.auth()).isNotEmpty();
    for (GatewayOperation operation : inventory.auth()) {
      assertThat(matchingRoutes(List.of(authRoute), operation.gatewayRoute()))
          .as("delegated /auth route for %s", operation.canonicalRoute())
          .singleElement()
          .isEqualTo(authRoute);
      assertThat(matchingRoutes(domainRoutes, operation.canonicalRoute()))
          .as("auth-service API must not enter a domain /api route directly")
          .isEmpty();
    }

    assertThat(inventory.serviceLocalHealth()).isNotEmpty();
    for (GatewayOperation operation : inventory.serviceLocalHealth()) {
      assertThat(matchingRoutes(domainRoutes, operation.canonicalRoute()))
          .as("service-local health is not gateway-routable: %s", operation.canonicalRoute())
          .isEmpty();
    }
  }

  @Test
  void plannerMapReadsStayOnTheirNginxApplicationRoute() throws Exception {
    ContractInventory inventory = contractInventory();
    List<RouteCandidate> routes = publicRoutes();

    assertThat(inventory.plannerApplication())
        .singleElement()
        .satisfies(
            operation -> {
              assertThat(operation.owner()).isEqualTo("logistics-planner-service");
              assertThat(operation.canonicalRoute())
                  .isEqualTo(new RouteKey(HttpMethod.GET, "/logistics-panel/api/map-settings"));
              assertThat(operation.security()).isEqualTo(SecurityRequirement.BEARER_JWT);
              assertThat(matchingRoutes(routes, operation.canonicalRoute())).isEmpty();
            });
    assertThat(
            matchingRoutes(
                routes, new RouteKey(HttpMethod.GET, "/api/logistics-planner/v1/map-settings")))
        .as("the operator map read must not acquire an invented gateway alias")
        .isEmpty();
  }

  private ContractInventory contractInventory() throws Exception {
    Path projectDirectory = Path.of(System.getProperty("rwms.test.project-dir"));
    Path openApiDirectory = projectDirectory.resolve("../../contracts/openapi").normalize();
    List<GatewayOperation> publicDomain = new ArrayList<>();
    List<GatewayOperation> internal = new ArrayList<>();
    List<GatewayOperation> auth = new ArrayList<>();
    List<GatewayOperation> serviceLocalHealth = new ArrayList<>();
    List<GatewayOperation> plannerApplication = new ArrayList<>();
    try (var files = Files.list(openApiDirectory)) {
      for (Path contract :
          files
              .filter(path -> path.getFileName().toString().endsWith("-service.yaml"))
              .sorted()
              .toList()) {
        String owner = contract.getFileName().toString().replaceFirst("[.]yaml$", "");
        Map<String, Object> document;
        try (InputStream input = Files.newInputStream(contract)) {
          document = map(new Yaml().load(input));
        }
        String serverPrefix = serverPrefix(document, contract);
        Object inheritedSecurity = document.get("security");
        Map<String, Object> securitySchemes =
            map(map(document.get("components")).get("securitySchemes"));
        for (var pathEntry : map(document.get("paths")).entrySet()) {
          Map<String, Object> pathItem = map(pathEntry.getValue());
          String pathServerPrefix =
              pathItem.containsKey("servers") ? serverPrefix(pathItem, contract) : serverPrefix;
          String canonicalPath = normalizePath(joinPaths(pathServerPrefix, pathEntry.getKey()));
          for (var methodEntry : pathItem.entrySet()) {
            String methodName = methodEntry.getKey().toLowerCase(Locale.ROOT);
            if (!HTTP_METHODS.contains(methodName)) {
              continue;
            }
            Map<String, Object> operation = map(methodEntry.getValue());
            Object security = operation.getOrDefault("security", inheritedSecurity);
            RouteKey canonicalRoute =
                new RouteKey(
                    HttpMethod.valueOf(methodName.toUpperCase(Locale.ROOT)), canonicalPath);
            SecurityRequirement requirement =
                securityRequirement(security, securitySchemes, owner + " " + canonicalRoute);
            if (canonicalPath.startsWith("/api/internal/")) {
              String gatewayAlias =
                  owner.equals(AUTH_OWNER)
                      ? "/auth" + canonicalPath
                      : internalAlias(owner, canonicalPath);
              internal.add(
                  new GatewayOperation(
                      owner,
                      canonicalRoute,
                      new RouteKey(canonicalRoute.method(), gatewayAlias),
                      requirement));
            } else if (owner.equals(AUTH_OWNER)) {
              auth.add(
                  new GatewayOperation(
                      owner,
                      canonicalRoute,
                      new RouteKey(canonicalRoute.method(), "/auth" + canonicalRoute.path()),
                      requirement));
            } else if ((owner.equals(MEDIA_OWNER) && canonicalPath.startsWith("/health/"))
                || (owner.equals(CAD_OWNER) && canonicalPath.equals("/health"))) {
              serviceLocalHealth.add(
                  new GatewayOperation(owner, canonicalRoute, canonicalRoute, requirement));
            } else if (owner.equals("logistics-planner-service")
                && canonicalPath.startsWith("/logistics-panel/api/")) {
              plannerApplication.add(
                  new GatewayOperation(owner, canonicalRoute, canonicalRoute, requirement));
            } else {
              assertThat(canonicalPath)
                  .as("supported public gateway namespace for %s", owner)
                  .startsWith("/api/");
              publicDomain.add(
                  new GatewayOperation(
                      owner,
                      canonicalRoute,
                      new RouteKey(
                          canonicalRoute.method(), publicGatewayPath(owner, canonicalPath)),
                      requirement));
            }
          }
        }
      }
    }
    Comparator<GatewayOperation> order =
        Comparator.comparing(GatewayOperation::gatewayRoute, ROUTE_ORDER)
            .thenComparing(GatewayOperation::owner);
    return new ContractInventory(
        publicDomain.stream().sorted(order).toList(),
        internal.stream().sorted(order).toList(),
        auth.stream().sorted(order).toList(),
        serviceLocalHealth.stream().sorted(order).toList(),
        plannerApplication.stream().sorted(order).toList());
  }

  private List<RouteCandidate> domainRoutes() {
    GatewayRouteConfiguration configuration = new GatewayRouteConfiguration();
    GatewayProperties properties = routeProperties();
    GatewayUpstreamProblemHandler upstream = mock(GatewayUpstreamProblemHandler.class);
    GatewayUpstreamProblemWriter upstreamWriter = mock(GatewayUpstreamProblemWriter.class);
    SseProxyHandler sse = mock(SseProxyHandler.class);
    HtmlImportCommitProxyHandler htmlImport = mock(HtmlImportCommitProxyHandler.class);
    MediaUploadContentProxyHandler mediaUpload = mock(MediaUploadContentProxyHandler.class);
    InventoryOutcomeRecalculateProxyHandler inventoryRecalculate =
        mock(InventoryOutcomeRecalculateProxyHandler.class);
    AssistantTurnsProxyHandler assistantTurns = mock(AssistantTurnsProxyHandler.class);
    CadProxyHandler cadProxy = mock(CadProxyHandler.class);
    List<RouteCandidate> routes =
        List.of(
            candidate(
                "task-board-service",
                "taskBoardWorkerEventsRoute",
                configuration.taskBoardWorkerEventsRoute(properties, upstream, sse)),
            candidate(
                "task-board-service",
                "taskBoardDriverEventsRoute",
                configuration.taskBoardDriverEventsRoute(properties, upstream, sse)),
            candidate(
                "asset-service",
                "assetEventsRoute",
                configuration.assetEventsRoute(properties, upstream, sse)),
            candidate(
                MEDIA_OWNER,
                "mediaEventsRoute",
                configuration.mediaEventsRoute(properties, upstream, sse)),
            candidate(
                "task-board-service",
                "taskBoardRoutes",
                configuration.taskBoardRoutes(properties, upstream)),
            candidate(
                "warehouse-service",
                "warehouseRoutes",
                configuration.warehouseRoutes(properties, upstream)),
            candidate(
                "asset-service",
                "htmlImportCommitRoute",
                configuration.htmlImportCommitRoute(properties, upstream, htmlImport)),
            candidate(
                "asset-service", "assetRoutes", configuration.assetRoutes(properties, upstream)),
            candidate(
                "maintenance-service",
                "maintenanceRoutes",
                configuration.maintenanceRoutes(properties, upstream)),
            candidate(
                MEDIA_OWNER,
                "mediaUploadContentRoute",
                configuration.mediaUploadContentRoute(properties, upstream, mediaUpload)),
            candidate(MEDIA_OWNER, "mediaRoutes", configuration.mediaRoutes(properties, upstream)),
            candidate(
                "inventory-service",
                "inventoryOutcomeRecalculateRoute",
                configuration.inventoryOutcomeRecalculateRoute(
                    properties, upstream, inventoryRecalculate)),
            candidate(
                "inventory-service",
                "inventoryRoutes",
                configuration.inventoryRoutes(properties, upstream)),
            candidate(
                "logistics-service",
                "logisticsRoutes",
                configuration.logisticsRoutes(properties, upstream)),
            candidate(
                "logistics-planner-service",
                "logisticsPlannerAdminRoutes",
                configuration.logisticsPlannerAdminRoutes(properties, upstream)),
            candidate(
                "assistant-service",
                "assistantTurnsRoute",
                configuration.assistantTurnsRoute(properties, upstream, assistantTurns)),
            candidate(
                "assistant-service",
                "assistantRoutes",
                configuration.assistantRoutes(properties, upstream)),
            candidate(
                "cad-service",
                "cadRoutes",
                configuration.cadRoutes(properties, upstream, upstreamWriter, cadProxy)),
            candidate(
                "dossier-service",
                "dossierRoutes",
                configuration.dossierRoutes(properties, upstream)),
            candidate(
                "analytics-service",
                "analyticsRoutes",
                configuration.analyticsRoutes(properties, upstream)));
    List<String> configuredDomainRoutes =
        Arrays.stream(GatewayRouteConfiguration.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Bean.class))
            .filter(method -> RouterFunction.class.isAssignableFrom(method.getReturnType()))
            .map(Method::getName)
            .filter(name -> !name.equals("authRoutes"))
            .sorted()
            .toList();
    assertThat(routes.stream().map(RouteCandidate::name).sorted().toList())
        .as("complete gateway domain RouterFunction bean inventory")
        .containsExactlyElementsOf(configuredDomainRoutes);
    return routes;
  }

  private List<RouteCandidate> publicRoutes() {
    GatewayRouteConfiguration configuration = new GatewayRouteConfiguration();
    GatewayUpstreamProblemHandler upstream = mock(GatewayUpstreamProblemHandler.class);
    List<RouteCandidate> routes = new ArrayList<>(domainRoutes());
    routes.add(
        candidate(
            AUTH_OWNER,
            "authRoutes",
            configuration.authRoutes(routeProperties(), upstream)));
    return List.copyOf(routes);
  }

  private static RouteCandidate candidate(
      String owner, String name, RouterFunction<ServerResponse> router) {
    Method beanMethod =
        Arrays.stream(GatewayRouteConfiguration.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(name))
            .findFirst()
            .orElseThrow();
    Order order = beanMethod.getAnnotation(Order.class);
    return new RouteCandidate(
        owner, name, order == null ? Ordered.LOWEST_PRECEDENCE : order.value(), router);
  }

  private static List<RouteCandidate> matchingRoutes(
      List<RouteCandidate> candidates, RouteKey route) {
    return candidates.stream()
        .filter(candidate -> matches(candidate.router(), route))
        .sorted(Comparator.comparingInt(RouteCandidate::order).thenComparing(RouteCandidate::name))
        .toList();
  }

  private static boolean matches(RouterFunction<ServerResponse> router, RouteKey route) {
    MockHttpServletRequest servletRequest =
        new MockHttpServletRequest(route.method().name(), concretePath(route.path()));
    ServerRequest request = ServerRequest.create(servletRequest, List.of());
    return router.route(request).isPresent();
  }

  private static RouteCandidate selectedRoute(List<RouteCandidate> candidates) {
    assertThat(candidates).isNotEmpty();
    return candidates.getFirst();
  }

  private static GatewayProperties routeProperties() {
    GatewayProperties properties = new GatewayProperties();
    properties.setPublicBaseUri(URI.create("https://panel.example"));
    properties.getSecurity().setIssuer("https://panel.example/auth");
    properties.getCors().setAllowedOrigins(List.of("https://panel.example"));
    properties.getRoutes().setAuthUri(URI.create("http://auth.test"));
    properties.getRoutes().setTaskBoardUri(URI.create("http://task-board.test"));
    properties.getRoutes().setWarehouseUri(URI.create("http://warehouse.test"));
    properties.getRoutes().setAssetUri(URI.create("http://asset.test"));
    properties.getRoutes().setMaintenanceUri(URI.create("http://maintenance.test"));
    properties.getRoutes().setMediaUri(URI.create("http://media.test"));
    properties.getRoutes().setInventoryUri(URI.create("http://inventory.test"));
    properties.getRoutes().setLogisticsUri(URI.create("http://logistics.test"));
    properties.getRoutes().setLogisticsPlannerUri(URI.create("http://logistics-planner.test"));
    properties.getRoutes().setDossierUri(URI.create("http://dossier.test"));
    properties.getRoutes().setAnalyticsUri(URI.create("http://analytics.test"));
    properties.getRoutes().setAssistantUri(URI.create("http://assistant.test"));
    properties.getRoutes().setCadUri(URI.create("http://cad.test"));
    return properties;
  }

  private static String serverPrefix(Map<String, Object> document, Path contract) {
    assertThat(document.get("servers")).as("servers in %s", contract).isInstanceOf(List.class);
    List<?> servers = (List<?>) document.get("servers");
    assertThat(servers).as("single server prefix in %s", contract).hasSize(1);
    return String.valueOf(map(servers.getFirst()).get("url"));
  }

  private static String publicGatewayPath(String owner, String canonicalPath) {
    if (owner.equals("task-board-service")) {
      return "/api/task-board" + canonicalPath.substring("/api".length());
    }
    if (owner.equals("analytics-service")) {
      return "/api/analytics" + canonicalPath.substring("/api".length());
    }
    return canonicalPath;
  }

  private static String internalAlias(String owner, String canonicalPath) {
    if (owner.equals("task-board-service")) {
      return "/api/task-board" + canonicalPath.substring("/api".length());
    }
    String publicPrefix = publicPrefix(owner);
    String ownerToken = publicPrefix.substring(publicPrefix.lastIndexOf('/') + 1);
    String canonicalPrefix = "/api/internal/" + ownerToken;
    assertThat(canonicalPath)
        .as("canonical internal owner prefix for %s", owner)
        .startsWith(canonicalPrefix + "/");
    return publicPrefix + "/internal" + canonicalPath.substring(canonicalPrefix.length());
  }

  private static String publicPrefix(String owner) {
    return switch (owner) {
      case "task-board-service" -> "/api/task-board";
      case "warehouse-service" -> "/api/warehouse";
      case "asset-service" -> "/api/asset";
      case "maintenance-service" -> "/api/maintenance";
      case MEDIA_OWNER -> "/api/media";
      case "inventory-service" -> "/api/inventory";
      case "logistics-service" -> "/api/logistics";
      case "dossier-service" -> "/api/dossier";
      case "analytics-service" -> "/api/analytics";
      case "assistant-service" -> "/api/assistant";
      default -> throw new IllegalArgumentException("No public gateway prefix for " + owner);
    };
  }

  private static SecurityRequirement securityRequirement(
      Object value, Map<String, Object> securitySchemes, String label) {
    assertThat(value).as("security for %s", label).isInstanceOf(List.class);
    List<?> requirements = (List<?>) value;
    if (requirements.isEmpty()) {
      return SecurityRequirement.ANONYMOUS;
    }
    if (requirements.size() == 2) {
      Map<String, Map<?, ?>> alternatives = new LinkedHashMap<>();
      for (Object alternative : requirements) {
        assertThat(alternative).as("security for %s", label).isInstanceOf(Map.class);
        Map<?, ?> requirement = (Map<?, ?>) alternative;
        assertThat(requirement).as("security alternative for %s", label).hasSize(1);
        Object schemeName = requirement.keySet().iterator().next();
        assertThat(schemeName).as("security scheme name for %s", label).isInstanceOf(String.class);
        alternatives.put((String) schemeName, requirement);
      }
      assertThat(alternatives.keySet())
          .as("alternative security schemes for %s", label)
          .containsExactlyInAnyOrder("bearerJwt", "guestInvitation");
      assertBearerScheme(securitySchemes, alternatives.get("bearerJwt"), "bearerJwt", label);
      assertCadGuestScheme(
          securitySchemes, alternatives.get("guestInvitation"), "guestInvitation", label);
      return SecurityRequirement.BEARER_JWT_OR_CAD_GUEST;
    }
    assertThat(requirements).as("security for %s", label).hasSize(1);
    assertThat(requirements.getFirst()).as("security for %s", label).isInstanceOf(Map.class);
    Map<?, ?> requirement = (Map<?, ?>) requirements.getFirst();
    if (requirement.keySet().equals(Set.of("csrfCookie", "csrfHeader"))) {
      assertCsrfScheme(
          securitySchemes, requirement, "csrfCookie", "cookie", "XSRF-TOKEN", label);
      assertCsrfScheme(
          securitySchemes, requirement, "csrfHeader", "header", "X-XSRF-TOKEN", label);
      // CSRF is enforced by auth-service after /auth is stripped; the stateless edge intentionally
      // treats this delegated operation as anonymous rather than duplicating token state.
      return SecurityRequirement.ANONYMOUS;
    }
    assertThat(requirement.size()).as("security scheme count for %s", label).isEqualTo(1);
    Object schemeName = requirement.keySet().iterator().next();
    assertThat(schemeName).as("security scheme name for %s", label).isInstanceOf(String.class);
    String scheme = (String) schemeName;
    assertBearerScheme(securitySchemes, requirement, scheme, label);
    return SecurityRequirement.BEARER_JWT;
  }

  private static void assertBearerScheme(
      Map<String, Object> securitySchemes,
      Map<?, ?> requirement,
      String scheme,
      String label) {
    assertThat(requirement.get(scheme)).as("bearer scopes for %s", label).isEqualTo(List.of());
    Map<String, Object> definition = map(securitySchemes.get(scheme));
    assertThat(definition.get("type")).as("security type for %s", label).isEqualTo("http");
    assertThat(definition.get("scheme")).as("security scheme for %s", label).isEqualTo("bearer");
    assertThat(definition.get("bearerFormat"))
        .as("bearer format for %s", label)
        .isEqualTo("JWT");
  }

  private static void assertCadGuestScheme(
      Map<String, Object> securitySchemes,
      Map<?, ?> requirement,
      String scheme,
      String label) {
    assertThat(requirement.get(scheme)).as("guest scopes for %s", label).isEqualTo(List.of());
    Map<String, Object> definition = map(securitySchemes.get(scheme));
    assertThat(definition.get("type")).as("guest type for %s", label).isEqualTo("apiKey");
    assertThat(definition.get("in")).as("guest location for %s", label).isEqualTo("header");
    assertThat(definition.get("name"))
        .as("guest header for %s", label)
        .isEqualTo(HttpHeaders.AUTHORIZATION);
  }

  /** Validates one half of the exact cookie-plus-header CSRF requirement delegated to auth-service. */
  private static void assertCsrfScheme(
      Map<String, Object> securitySchemes,
      Map<?, ?> requirement,
      String scheme,
      String location,
      String name,
      String label) {
    assertThat(requirement.get(scheme)).as("CSRF scopes for %s", label).isEqualTo(List.of());
    Map<String, Object> definition = map(securitySchemes.get(scheme));
    assertThat(definition.get("type")).as("CSRF type for %s", label).isEqualTo("apiKey");
    assertThat(definition.get("in")).as("CSRF location for %s", label).isEqualTo(location);
    assertThat(definition.get("name")).as("CSRF name for %s", label).isEqualTo(name);
  }

  private static Map<RouteKey, String> specialRoutes() {
    return Map.ofEntries(
        Map.entry(
            new RouteKey(HttpMethod.GET, "/api/task-board/worker/v1/events"),
            "taskBoardWorkerEventsRoute"),
        Map.entry(
            new RouteKey(HttpMethod.GET, "/api/task-board/driver/v1/events"),
            "taskBoardDriverEventsRoute"),
        Map.entry(new RouteKey(HttpMethod.GET, "/api/asset/v1/events"), "assetEventsRoute"),
        Map.entry(new RouteKey(HttpMethod.GET, "/api/media/v1/events"), "mediaEventsRoute"),
        Map.entry(
            new RouteKey(HttpMethod.POST, "/api/asset/v1/html-imports/{}/commit"),
            "htmlImportCommitRoute"),
        Map.entry(
            new RouteKey(HttpMethod.PUT, "/api/media/v1/upload-sessions/{}/content"),
            "mediaUploadContentRoute"),
        Map.entry(
            new RouteKey(
                HttpMethod.PUT,
                "/api/media/v1/upload-sessions/{}/variants/{}/content"),
            "mediaUploadContentRoute"),
        Map.entry(
            new RouteKey(
                HttpMethod.POST,
                "/api/inventory/v1/sessions/{}/outcome/recalculate"),
            "inventoryOutcomeRecalculateRoute"),
        Map.entry(
            new RouteKey(HttpMethod.POST, "/api/assistant/v1/conversations/{}/turns"),
            "assistantTurnsRoute"));
  }

  private static String joinPaths(String prefix, String path) {
    String left = stripTrailingSlash(prefix);
    String right = path.startsWith("/") ? path.substring(1) : path;
    if (left.isEmpty() || left.equals("/")) {
      return "/" + right;
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

  /** Exact method and normalized path key for one canonical or gateway operation. */
  private record RouteKey(HttpMethod method, String path) {}

  /** One canonical operation and the path/security expected at the public gateway. */
  private record GatewayOperation(
      String owner,
      RouteKey canonicalRoute,
      RouteKey gatewayRoute,
      SecurityRequirement security) {}

  /** Exhaustive partition of canonical operations relevant to gateway exposure. */
  private record ContractInventory(
      List<GatewayOperation> publicDomain,
      List<GatewayOperation> internal,
      List<GatewayOperation> auth,
      List<GatewayOperation> serviceLocalHealth,
      List<GatewayOperation> plannerApplication) {}

  /** One executable gateway router bean together with its owner and effective Spring order. */
  private record RouteCandidate(
      String owner, String name, int order, RouterFunction<ServerResponse> router) {}

  /** Supported canonical authentication classifications at the public edge. */
  private enum SecurityRequirement {
    BEARER_JWT,
    BEARER_JWT_OR_CAD_GUEST,
    ANONYMOUS
  }

  /** Supplies only the infrastructure required to build the production gateway security chain. */
  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  @Import(GatewaySecurityProblemWriter.class)
  static class SecurityTestBeans {
    @Bean
    CorrelationIdFilter correlationIdFilter() {
      return new CorrelationIdFilter();
    }

    @Bean
    RwmsProblemDetailFactory rwmsProblemDetailFactory() {
      return new RwmsProblemDetailFactory();
    }

    @Bean
    @Primary
    GatewayProperties gatewayProperties() {
      GatewayProperties properties = new GatewayProperties();
      properties.setPublicBaseUri(URI.create("https://panel.example"));
      properties.getRoutes().setAuthUri(URI.create("http://auth.test"));
      properties.getSecurity().setIssuer("https://panel.example/auth");
      properties.getCors().setAllowedOrigins(List.of("https://panel.example"));
      return properties;
    }

    @Bean
    JwtAudienceValidatorFactory jwtAudienceValidatorFactory() {
      return new JwtAudienceValidatorFactory();
    }

    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
      return token ->
          Jwt.withTokenValue(token)
              .header("alg", "none")
              .subject("route-parity-probe")
              .audience(List.of("rwms-services"))
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
