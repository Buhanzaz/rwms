package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Fail-closed authority boundary for the reviewed legacy catalog snapshot. */
@Component
public class ReviewedLegacyCatalogManifest {
  static final String MANIFEST_RESOURCE = "legacy/maintenance-catalog-manifest.json";
  static final String ARTIFACT_RESOURCE = "legacy/maintenance-catalog-v1.json";
  static final String MANIFEST_SHA256 =
      "cb82fadc291a111d2924cb4e6b20230b0aa0b0976b1e637d5d310e62b6478953";
  static final String ARTIFACT_SHA256 =
      "c35ff6611aeb6e6c711eb9e6181b20f325f2b1db349f04fc155f9839fae2411d";
  static final UUID SOURCE_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  static final String SOURCE_SHA256 =
      "94bacdcf7114e9dfb1935a162b29714e25ed36b406980c3b9551c7d27c687721";
  static final String NODE_SHA256 =
      "a5546cbbc9c48c630e987c4e831eebee47f70413b467ac0fdc21e5ab3b510873";
  static final String LINK_SHA256 =
      "3a20b83e2392ff8506a8525e6a9762c132d3a9957679151d4964c4335536e883";
  static final String QUEUE_SHA256 =
      "f486489d601cff0ddd85228516f1527277f8b90ec6fec6ee11e8aebcdd569766";
  static final String APPROVED_MAPPING_POLICY_SHA256 =
      "c378e395ec2931b1639dac8547659a1f749530edf791c1db9c524dc66d1b6c85";
  static final String APPROVED_MAPPING_SHA256 =
      "118aed23abfebeb213dc9dc37b415def2b8b0272e94971f2e2ab93d80ed280a2";

  private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,63}$");
  private static final Pattern PRICE = Pattern.compile("^(?:0|[1-9][0-9]*)(?:\\.[0-9]{2})$");

  private final ObjectMapper mapper;
  private final Manifest manifest;
  private final ApprovedArtifact artifact;

  public ReviewedLegacyCatalogManifest(ObjectMapper mapper) {
    this.mapper = mapper;
    ObjectMapper strictMapper = strictMapper(mapper);
    byte[] manifestBytes = readBytes(MANIFEST_RESOURCE);
    byte[] artifactBytes = readBytes(ARTIFACT_RESOURCE);
    if (!MANIFEST_SHA256.equals(MaintenanceChecksum.sha256(manifestBytes))
        || !ARTIFACT_SHA256.equals(MaintenanceChecksum.sha256(artifactBytes))) {
      throw new IllegalStateException("Packaged reviewed catalog resource hash is invalid");
    }
    this.manifest = read(strictMapper, manifestBytes, MANIFEST_RESOURCE, Manifest.class);
    this.artifact = read(strictMapper, artifactBytes, ARTIFACT_RESOURCE, ApprovedArtifact.class);
    List<FieldViolation> authorityIssues = new ArrayList<>();
    validateAuthorityManifest(authorityIssues);
    if (!authorityIssues.isEmpty()) {
      throw new IllegalStateException(
          "Packaged reviewed catalog authority is invalid: "
              + authorityIssues.stream().map(FieldViolation::code).distinct().toList());
    }
  }

  public Review validate(ImportCatalogRequest request) {
    List<FieldViolation> issues = new ArrayList<>();
    validateAuthorityManifest(issues);
    if (!SOURCE_WAREHOUSE_ID.equals(request.warehouseId())) {
      issues.add(
          issue(
              "warehouseId",
              "REVIEWED_SOURCE_WAREHOUSE_MISMATCH",
              "Reviewed legacy routing snapshots are bound to their source warehouse"));
    }
    if (!SOURCE_SHA256.equals(request.sourceSha256())) {
      issues.add(
          issue(
              "sourceSha256",
              "REVIEWED_SOURCE_HASH_MISMATCH",
              "sourceSha256 does not identify the reviewed legacy catalog evidence"));
    }
    if (request.nodes().size() != manifest.nodeCount()) {
      issues.add(
          issue(
              "nodes",
              "REVIEWED_NODE_COUNT_MISMATCH",
              "Reviewed legacy catalog requires exactly " + manifest.nodeCount() + " nodes"));
    }
    if (request.links().size() != manifest.linkCount()) {
      issues.add(
          issue(
              "links",
              "REVIEWED_LINK_COUNT_MISMATCH",
              "Reviewed legacy catalog requires exactly " + manifest.linkCount() + " links"));
    }
    compareDistribution(
        "nodes.nodeType", manifest.nodeTypes(), nodeDistribution(request.nodes()), issues);
    compareDistribution(
        "links.linkType", manifest.linkTypes(), linkDistribution(request.links()), issues);
    validateRequestIdentitiesAndReferences(request, issues);
    validateSanitizedRequest(request, issues);

    String mappingSha256 = mappingSha256(request.nodes(), request.links());
    if (!APPROVED_MAPPING_SHA256.equals(mappingSha256)
        || !APPROVED_MAPPING_SHA256.equals(manifest.approvedMappingSha256())) {
      issues.add(
          issue(
              "mapping",
              "REVIEWED_MAPPING_HASH_MISMATCH",
              "Catalog payload does not match the approved target mapping artifact"));
    }
    if (!issues.isEmpty()) {
      throw new MaintenanceCatalogImportValidationException(issues);
    }
    return new Review(
        MANIFEST_SHA256,
        ARTIFACT_SHA256,
        manifest.sourceEvidenceSha256(),
        manifest.nodeEvidenceSha256(),
        manifest.linkEvidenceSha256(),
        manifest.queueEvidenceSha256(),
        mappingSha256,
        manifest.nodeCount(),
        manifest.linkCount(),
        manifest.nodeTypes().get("MATERIAL"),
        10,
        artifact.routingSnapshots().size(),
        manifest.nodeTypes(),
        manifest.linkTypes());
  }

  ImportCatalogRequest approvedRequest() {
    return approvedRequest(SOURCE_WAREHOUSE_ID);
  }

  ImportCatalogRequest approvedRequest(UUID warehouseId) {
    return new ImportCatalogRequest(
        warehouseId, SOURCE_SHA256, artifact.nodes(), artifact.links());
  }

  List<RoutingSnapshot> routingSnapshots() {
    return artifact.routingSnapshots();
  }

  Review validatePersistedSnapshot(
      UUID warehouseId, List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {
    List<CatalogNodeInput> normalizedNodes =
        nodes.stream()
            .map(
                node ->
                    new CatalogNodeInput(
                        node.id(),
                        node.code(),
                        node.nodeType(),
                        node.name(),
                        node.active(),
                        node.parentNodeId(),
                        false,
                        null,
                        node.unit(),
                        node.unitPrice(),
                        node.durationMinutes(),
                        node.includeInEstimate(),
                        node.commonItem(),
                        node.showInMainMenu(),
                        node.photoRequired(),
                        node.routing(),
                        List.of(),
                        null,
                        List.of()))
            .sorted(Comparator.comparing(node -> node.id().toString()))
            .toList();
    List<CatalogLinkInput> normalizedLinks =
        links.stream()
            .sorted(Comparator.comparing(link -> link.id().toString()))
            .toList();
    return validate(
        new ImportCatalogRequest(warehouseId, SOURCE_SHA256, normalizedNodes, normalizedLinks));
  }

  static void assertStrictResourceShape(
      ObjectMapper mapper, String manifestJson, String artifactJson) {
    ObjectMapper strictMapper = strictMapper(mapper);
    readJson(strictMapper, manifestJson, Manifest.class, MANIFEST_RESOURCE);
    readJson(strictMapper, artifactJson, ApprovedArtifact.class, ARTIFACT_RESOURCE);
  }

  private void validateAuthorityManifest(List<FieldViolation> issues) {
    if (manifest.manifestVersion() != 1
        || !SOURCE_WAREHOUSE_ID.equals(manifest.sourceWarehouseId())
        || !SOURCE_SHA256.equals(manifest.sourceEvidenceSha256())
        || !NODE_SHA256.equals(manifest.nodeEvidenceSha256())
        || !LINK_SHA256.equals(manifest.linkEvidenceSha256())
        || !QUEUE_SHA256.equals(manifest.queueEvidenceSha256())
        || manifest.nodeCount() != 232
        || manifest.linkCount() != 254
        || manifest.brokenLinkReferences() != 0
        || !manifest.importReady()
        || !APPROVED_MAPPING_POLICY_SHA256.equals(manifest.approvedMappingPolicySha256())
        || !APPROVED_MAPPING_SHA256.equals(manifest.approvedMappingSha256())
        || manifest.unresolvedMappings() == null
        || !manifest.unresolvedMappings().isEmpty()
        || !expectedNodeTypes().equals(manifest.nodeTypes())
        || !expectedLinkTypes().equals(manifest.linkTypes())) {
      issues.add(
          issue(
              "mapping",
              "REVIEWED_MANIFEST_INVALID",
              "Packaged reviewed catalog manifest does not match approved evidence"));
    }
    validateApprovedArtifact(issues);
  }

  private void validateApprovedArtifact(List<FieldViolation> issues) {
    if (artifact.artifactVersion() != 1
        || !SOURCE_WAREHOUSE_ID.equals(artifact.sourceWarehouseId())
        || !SOURCE_SHA256.equals(artifact.sourceEvidenceSha256())
        || !NODE_SHA256.equals(artifact.nodeEvidenceSha256())
        || !LINK_SHA256.equals(artifact.linkEvidenceSha256())
        || !QUEUE_SHA256.equals(artifact.queueEvidenceSha256())
        || !APPROVED_MAPPING_POLICY_SHA256.equals(artifact.approvedMappingPolicySha256())
        || !APPROVED_MAPPING_SHA256.equals(artifact.approvedMappingSha256())
        || !APPROVED_MAPPING_POLICY_SHA256.equals(policySha256(artifact.mappingPolicy()))
        || artifact.nodes().size() != 232
        || artifact.links().size() != 254
        || !expectedNodeTypes().equals(nodeDistribution(artifact.nodes()))
        || !expectedLinkTypes().equals(linkDistribution(artifact.links()))
        || !APPROVED_MAPPING_SHA256.equals(
            mappingSha256(artifact.nodes(), artifact.links()))
        || !idsAreStrictlySorted(artifact.nodes().stream().map(CatalogNodeInput::id).toList())
        || !idsAreStrictlySorted(artifact.links().stream().map(CatalogLinkInput::id).toList())) {
      issues.add(
          issue(
              "mapping",
              "REVIEWED_ARTIFACT_INVALID",
              "Packaged catalog artifact does not match the approved deterministic mapping"));
    }
    if (!expectedRoutingSnapshots().equals(artifact.routingSnapshots())) {
      issues.add(
          issue(
              "routing",
              "REVIEWED_ROUTING_EVIDENCE_INVALID",
              "Packaged routing snapshots do not match separately reviewed queue evidence"));
    }
    validateArtifactGraph(artifact.nodes(), artifact.links(), issues);
    validateSanitizedArtifact(issues);
  }

  private void validateArtifactGraph(
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links,
      List<FieldViolation> issues) {
    Set<UUID> nodeIds = new HashSet<>();
    Set<String> nodeCodes = new HashSet<>();
    Map<UUID, List<UUID>> parents = new HashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (node == null
          || node.id() == null
          || node.code() == null
          || !CODE.matcher(node.code()).matches()
          || node.name() == null
          || node.name().isBlank()
          || node.name().length() > 255
          || node.nodeType() == null
          || node.active() == null
          || node.durationMinutes() == null
          || node.durationMinutes() < 0
          || node.durationMinutes() > 525600
          || (node.unit() != null && node.unit().length() > 32)
          || (node.unitPrice() != null && !PRICE.matcher(node.unitPrice()).matches())
          || !nodeIds.add(node.id())
          || !nodeCodes.add(node.code())) {
        issues.add(
            issue(
                "nodes",
                "REVIEWED_NODE_INVALID",
                "Approved artifact contains an invalid or duplicate catalog node"));
        continue;
      }
      if (node.parentNodeId() != null) {
        parents.computeIfAbsent(node.id(), ignored -> new ArrayList<>()).add(node.parentNodeId());
      }
    }
    boolean parentReferencesValid =
        nodes.stream()
            .filter(Objects::nonNull)
            .allMatch(node -> node.parentNodeId() == null || nodeIds.contains(node.parentNodeId()));
    if (!parentReferencesValid || containsCycle(nodeIds, parents)) {
      issues.add(
          issue(
              "nodes.parentNodeId",
              "REVIEWED_PARENT_GRAPH_INVALID",
              "Approved artifact parent hierarchy is invalid"));
    }

    Set<UUID> linkIds = new HashSet<>();
    Set<String> typedEdges = new HashSet<>();
    Map<UUID, List<UUID>> dependencies = new HashMap<>();
    for (CatalogLinkInput link : links) {
      if (link == null
          || link.id() == null
          || link.fromNodeId() == null
          || link.toNodeId() == null
          || link.linkType() == null
          || link.sortOrder() < 0
          || !linkIds.add(link.id())
          || !nodeIds.contains(link.fromNodeId())
          || !nodeIds.contains(link.toNodeId())
          || link.fromNodeId().equals(link.toNodeId())) {
        issues.add(
            issue(
                "links",
                "REVIEWED_LINK_INVALID",
                "Approved artifact contains an invalid catalog link"));
        continue;
      }
      String edge = link.fromNodeId() + ":" + link.toNodeId() + ":" + link.linkType();
      if (!typedEdges.add(edge)) {
        issues.add(
            issue(
                "links",
                "REVIEWED_LINK_INVALID",
                "Approved artifact contains a duplicate typed link"));
      }
      if (link.linkType() == CatalogLinkType.DEPENDENCY) {
        dependencies
            .computeIfAbsent(link.fromNodeId(), ignored -> new ArrayList<>())
            .add(link.toNodeId());
      }
    }
    if (containsCycle(nodeIds, dependencies)) {
      issues.add(
          issue(
              "links",
              "REVIEWED_DEPENDENCY_GRAPH_INVALID",
              "Approved artifact dependency graph is cyclic"));
    }
  }

  private void validateSanitizedArtifact(List<FieldViolation> issues) {
    Set<RoutingSnapshot> reviewedRouting = Set.copyOf(artifact.routingSnapshots());
    long routedNodes = artifact.nodes().stream().filter(node -> node.routing() != null).count();
    boolean unsafeNode =
        artifact.nodes().stream()
            .anyMatch(
                node ->
                    node.comment() != null
                        || !node.references().isEmpty()
                        || !node.mediaReferences().isEmpty()
                        || (node.routing() != null && !reviewedRouting.contains(node.routing())));
    boolean policyInvalid =
        artifact.mappingPolicy() == null
            || artifact.mappingPolicy().piiAllowed()
            || artifact.mappingPolicy().policyVersion() != 1
            || !artifact.mappingPolicy().excludedLegacyFields().contains("NODE.SORT_ORDER")
            || !artifact.mappingPolicy().excludedLegacyFields().contains("LINK.ACTIVE")
            || !artifact.mappingPolicy().excludedLegacyFields().contains("NODE.COMMENT_")
            || !artifact.mappingPolicy().excludedLegacyFields().contains("LINK.COMMENT_")
            || !"0".equals(artifact.mappingPolicy().defaults().nullDurationMinutes())
            || !"0".equals(artifact.mappingPolicy().defaults().nullLinkSortOrder());
    if (unsafeNode || routedNodes != 10 || policyInvalid) {
      issues.add(
          issue(
              "mapping",
              "REVIEWED_SANITIZATION_INVALID",
              "Approved artifact contains excluded data or unreviewed routing"));
    }
  }

  private void validateRequestIdentitiesAndReferences(
      ImportCatalogRequest request, List<FieldViolation> issues) {
    Map<UUID, CatalogNodeInput> approvedNodes =
        artifact.nodes().stream()
            .collect(Collectors.toMap(CatalogNodeInput::id, Function.identity()));
    Map<UUID, CatalogNodeInput> requestedNodes = uniqueNodes(request.nodes());
    if (!approvedNodes.keySet().equals(requestedNodes.keySet())) {
      issues.add(
          issue(
              "nodes.id",
              "REVIEWED_NODE_ID_MISMATCH",
              "Catalog node identities do not match reviewed legacy identities"));
    }
    requestedNodes.forEach(
        (id, node) -> {
          CatalogNodeInput approved = approvedNodes.get(id);
          if (approved == null) {
            return;
          }
          if (node.nodeType() != approved.nodeType()) {
            issues.add(
                issue(
                    "nodes.nodeType",
                    "REVIEWED_NODE_TYPE_MISMATCH",
                    "Catalog node type does not match reviewed evidence"));
          }
          if (!Objects.equals(node.parentNodeId(), approved.parentNodeId())) {
            issues.add(
                issue(
                    "nodes.parentNodeId",
                    "REVIEWED_NODE_REFERENCE_MISMATCH",
                    "Catalog parent reference does not match reviewed evidence"));
          }
          if (!Objects.equals(node.routing(), approved.routing())) {
            issues.add(
                issue(
                    "nodes.routing",
                    "REVIEWED_ROUTING_MISMATCH",
                    "Catalog routing does not match reviewed queue evidence"));
          }
        });

    Map<UUID, CatalogLinkInput> approvedLinks =
        artifact.links().stream()
            .collect(Collectors.toMap(CatalogLinkInput::id, Function.identity()));
    Map<UUID, CatalogLinkInput> requestedLinks = uniqueLinks(request.links());
    if (!approvedLinks.keySet().equals(requestedLinks.keySet())) {
      issues.add(
          issue(
              "links.id",
              "REVIEWED_LINK_ID_MISMATCH",
              "Catalog link identities do not match reviewed legacy identities"));
    }
    requestedLinks.forEach(
        (id, link) -> {
          CatalogLinkInput approved = approvedLinks.get(id);
          if (approved == null) {
            return;
          }
          if (!Objects.equals(link.fromNodeId(), approved.fromNodeId())
              || !Objects.equals(link.toNodeId(), approved.toNodeId())) {
            issues.add(
                issue(
                    "links",
                    "REVIEWED_LINK_REFERENCE_MISMATCH",
                    "Catalog link endpoints do not match reviewed evidence"));
          }
          if (link.linkType() != approved.linkType()) {
            issues.add(
                issue(
                    "links.linkType",
                    "REVIEWED_LINK_TYPE_MISMATCH",
                    "Catalog link type does not match reviewed evidence"));
          }
        });
  }

  private static Map<UUID, CatalogNodeInput> uniqueNodes(List<CatalogNodeInput> nodes) {
    Map<UUID, CatalogNodeInput> result = new LinkedHashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (node != null && node.id() != null) {
        result.putIfAbsent(node.id(), node);
      }
    }
    return result;
  }

  private static Map<UUID, CatalogLinkInput> uniqueLinks(List<CatalogLinkInput> links) {
    Map<UUID, CatalogLinkInput> result = new LinkedHashMap<>();
    for (CatalogLinkInput link : links) {
      if (link != null && link.id() != null) {
        result.putIfAbsent(link.id(), link);
      }
    }
    return result;
  }

  private static void validateSanitizedRequest(
      ImportCatalogRequest request, List<FieldViolation> issues) {
    boolean excludedDataPresent =
        request.nodes().stream()
            .filter(Objects::nonNull)
            .anyMatch(
                node ->
                    node.comment() != null
                        || Boolean.TRUE.equals(node.furnitureCategory())
                        || node.furnitureEquipment() != null
                        || (node.references() != null && !node.references().isEmpty())
                        || (node.mediaReferences() != null && !node.mediaReferences().isEmpty()));
    if (excludedDataPresent) {
      issues.add(
          issue(
              "nodes",
              "REVIEWED_EXCLUDED_FIELD_PRESENT",
              "Reviewed import excludes comments, arbitrary references and media metadata"));
    }
  }

  private String mappingSha256(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {
    try {
      return MaintenanceChecksum.sha256(
          mapper.writeValueAsBytes(new MappingArtifact(
              nodes.stream().map(ReviewedCatalogNodeMapping::from).toList(), links)));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Catalog mapping cannot be hashed", exception);
    }
  }

  private String policySha256(MappingPolicy policy) {
    try {
      return MaintenanceChecksum.sha256(mapper.writeValueAsBytes(policy));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Catalog mapping policy cannot be hashed", exception);
    }
  }

  private static Map<String, Integer> nodeDistribution(List<CatalogNodeInput> nodes) {
    Map<String, Integer> values = new java.util.TreeMap<>();
    nodes.stream()
        .filter(Objects::nonNull)
        .filter(node -> node.nodeType() != null)
        .forEach(node -> values.merge(node.nodeType().name(), 1, Integer::sum));
    return Map.copyOf(values);
  }

  private static Map<String, Integer> linkDistribution(List<CatalogLinkInput> links) {
    Map<String, Integer> values = new java.util.TreeMap<>();
    links.stream()
        .filter(Objects::nonNull)
        .filter(link -> link.linkType() != null)
        .forEach(link -> values.merge(link.linkType().name(), 1, Integer::sum));
    return Map.copyOf(values);
  }

  private static void compareDistribution(
      String field,
      Map<String, Integer> expected,
      Map<String, Integer> actual,
      List<FieldViolation> issues) {
    if (!expected.equals(actual)) {
      issues.add(
          issue(
              field,
              "REVIEWED_DISTRIBUTION_MISMATCH",
              "Distribution does not match approved legacy evidence"));
    }
  }

  private static boolean idsAreStrictlySorted(List<UUID> ids) {
    if (ids.stream().anyMatch(Objects::isNull)) {
      return false;
    }
    for (int index = 1; index < ids.size(); index++) {
      if (ids.get(index - 1).toString().compareTo(ids.get(index).toString()) >= 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsCycle(Set<UUID> ids, Map<UUID, List<UUID>> edges) {
    Set<UUID> visiting = new HashSet<>();
    Set<UUID> visited = new HashSet<>();
    for (UUID id : ids) {
      if (visit(id, edges, visiting, visited)) {
        return true;
      }
    }
    return false;
  }

  private static boolean visit(
      UUID id, Map<UUID, List<UUID>> edges, Set<UUID> visiting, Set<UUID> visited) {
    if (visited.contains(id)) {
      return false;
    }
    if (!visiting.add(id)) {
      return true;
    }
    for (UUID target : edges.getOrDefault(id, List.of())) {
      if (visit(target, edges, visiting, visited)) {
        return true;
      }
    }
    visiting.remove(id);
    visited.add(id);
    return false;
  }

  private static Map<String, Integer> expectedNodeTypes() {
    Map<String, Integer> result = new LinkedHashMap<>();
    result.put("CATEGORY", 10);
    result.put("SUBCATEGORY", 30);
    result.put("WORK", 89);
    result.put("MATERIAL", 91);
    result.put("LOCATION", 3);
    result.put("OPTION", 9);
    return Map.copyOf(result);
  }

  private static Map<String, Integer> expectedLinkTypes() {
    return Map.of("DEPENDENCY", 72, "FOLLOW_UP", 182);
  }

  private static List<RoutingSnapshot> expectedRoutingSnapshots() {
    return List.of(
        new RoutingSnapshot(
            UUID.fromString("019f21e8-4526-7462-95e8-3309ce19fc9c"),
            "EXTERNAL_WORKS",
            "REPAIR"),
        new RoutingSnapshot(
            UUID.fromString("019f21e8-cd97-7a20-a876-8306e77f94bd"),
            "INTERNAL_WORKS",
            "REPAIR"),
        new RoutingSnapshot(
            UUID.fromString("019f21e9-6057-736c-8999-6808191a1362"),
            "ELECTRICS",
            "REPAIR"),
        new RoutingSnapshot(
            UUID.fromString("019f21e9-f54f-7184-954e-29f237b9c424"),
            "PLUMBING",
            "REPAIR"),
        new RoutingSnapshot(
            UUID.fromString("019f21ea-6015-753f-ad3a-be58947aa252"),
            "WELDING",
            "REPAIR"),
        new RoutingSnapshot(
            UUID.fromString("019f21ed-eb53-782d-a73f-a24357e262b2"),
            "SANITARY_DISINFECTION",
            "HOLDING"));
  }

  private static FieldViolation issue(String field, String code, String message) {
    return new FieldViolation(field, code, message);
  }

  private static byte[] readBytes(String resource) {
    try (InputStream input = new ClassPathResource(resource).getInputStream()) {
      return input.readAllBytes();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Reviewed legacy catalog resource cannot be loaded: " + resource, exception);
    }
  }

  private static <T> T read(
      ObjectMapper mapper, byte[] json, String resource, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Reviewed legacy catalog resource has an invalid strict shape: " + resource,
          exception);
    }
  }

  private static <T> T readJson(
      ObjectMapper mapper, String json, Class<T> type, String resource) {
    try {
      return mapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Reviewed legacy catalog resource has an invalid strict shape: " + resource,
          exception);
    }
  }

  private static ObjectMapper strictMapper(ObjectMapper mapper) {
    return mapper
        .rebuild()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  public record Review(
      String manifestSha256,
      String artifactSha256,
      String sourceEvidenceSha256,
      String nodeEvidenceSha256,
      String linkEvidenceSha256,
      String queueEvidenceSha256,
      String mappingSha256,
      int nodeCount,
      int linkCount,
      int materialCount,
      int routedNodeCount,
      int routingSnapshotCount,
      Map<String, Integer> nodeTypes,
      Map<String, Integer> linkTypes) {}

  /**
   * Exact reviewed-v1 projection. Furniture metadata was not part of the legacy evidence and is
   * therefore both rejected on import and excluded from the immutable reviewed mapping hash.
   */
  private record ReviewedCatalogNodeMapping(
      UUID id,
      String code,
      CatalogNodeType nodeType,
      String name,
      Boolean active,
      UUID parentNodeId,
      String unit,
      String unitPrice,
      Integer durationMinutes,
      Boolean includeInEstimate,
      Boolean commonItem,
      Boolean showInMainMenu,
      Boolean photoRequired,
      RoutingSnapshot routing,
      List<OpaqueCatalogReference> references,
      String comment,
      List<MediaReferenceInput> mediaReferences) {
    private static ReviewedCatalogNodeMapping from(CatalogNodeInput node) {
      return new ReviewedCatalogNodeMapping(
          node.id(),
          node.code(),
          node.nodeType(),
          node.name(),
          node.active(),
          node.parentNodeId(),
          node.unit(),
          node.unitPrice(),
          node.durationMinutes(),
          node.includeInEstimate(),
          node.commonItem(),
          node.showInMainMenu(),
          node.photoRequired(),
          node.routing(),
          node.references(),
          node.comment(),
          node.mediaReferences());
    }
  }

  private record MappingArtifact(
      List<ReviewedCatalogNodeMapping> nodes, List<CatalogLinkInput> links) {}

  private record Defaults(String nullDurationMinutes, String nullLinkSortOrder) {}

  private record MappingPolicy(
      int policyVersion,
      List<String> preservedLegacyFields,
      Defaults defaults,
      List<String> excludedLegacyFields,
      String routingAuthority,
      boolean piiAllowed) {}

  private record ApprovedArtifact(
      int artifactVersion,
      UUID sourceWarehouseId,
      String sourceEvidenceSha256,
      String nodeEvidenceSha256,
      String linkEvidenceSha256,
      String queueEvidenceSha256,
      String approvedMappingPolicySha256,
      String approvedMappingSha256,
      MappingPolicy mappingPolicy,
      List<RoutingSnapshot> routingSnapshots,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links) {
    private ApprovedArtifact {
      routingSnapshots = List.copyOf(routingSnapshots);
      nodes = List.copyOf(nodes);
      links = List.copyOf(links);
    }
  }

  private record Manifest(
      int manifestVersion,
      UUID sourceWarehouseId,
      String sourceEvidenceSha256,
      String nodeEvidenceSha256,
      String linkEvidenceSha256,
      String queueEvidenceSha256,
      int nodeCount,
      int linkCount,
      Map<String, Integer> nodeTypes,
      Map<String, Integer> linkTypes,
      int brokenLinkReferences,
      boolean importReady,
      String approvedMappingPolicySha256,
      String approvedMappingSha256,
      List<String> unresolvedMappings) {}
}
