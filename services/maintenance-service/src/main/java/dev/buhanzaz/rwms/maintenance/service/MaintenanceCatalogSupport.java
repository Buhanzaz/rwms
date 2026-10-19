package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.mapper.CatalogFurnitureReferenceMapper;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.platform.contracts.FieldViolation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Provides catalog validation, routing canonicalization and catalog-content persistence mechanics. */
@Service
final class MaintenanceCatalogSupport {
  private final CatalogVersionRepository catalogVersions;
  private final CatalogNodeRepository catalogNodes;
  private final CatalogLinkRepository catalogLinks;
  private final MaintenanceReconciliationStore reconciliations;
  private final CatalogFurnitureReferenceMapper catalogFurnitureMapper;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceCatalogModelSupport catalogModelSupport;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateSupport estimateSupport;

  MaintenanceCatalogSupport(
      CatalogVersionRepository catalogVersions,
      CatalogNodeRepository catalogNodes,
      CatalogLinkRepository catalogLinks,
      MaintenanceReconciliationStore reconciliations,
      CatalogFurnitureReferenceMapper catalogFurnitureMapper,
      MaintenanceDependencyGateway dependencies,
      MaintenanceCatalogModelSupport catalogModelSupport,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateSupport estimateSupport) {
    this.catalogVersions = catalogVersions;
    this.catalogNodes = catalogNodes;
    this.catalogLinks = catalogLinks;
    this.reconciliations = reconciliations;
    this.catalogFurnitureMapper = catalogFurnitureMapper;
    this.dependencies = dependencies;
    this.catalogModelSupport = catalogModelSupport;
    this.commandSupport = commandSupport;
    this.estimateSupport = estimateSupport;
  }

  protected CatalogVersion requireMutableCatalog(UUID id, long expectedVersion, boolean lock) {
    CatalogVersion version = lock
        ? catalogVersions.findByIdForUpdate(id)
            .orElseThrow(() -> new MaintenanceNotFoundException("Catalog version not found"))
        : catalogModelSupport.requireCatalog(id);
    commandSupport.assertVersion(version.getVersion(), expectedVersion);
    if (version.getState() == CatalogVersionState.SUPERSEDED) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT", "Superseded catalog versions are immutable");
    }
    return version;
  }

  protected Map<UUID, String> resolveCabinCharacteristicNames(
      List<CatalogNodeInput> nodes) {
    commandSupport.requireNoCallerTransaction("resolve cabin characteristic truth");
    Set<UUID> requestedIds =
        nodes.stream()
            .map(CatalogNodeInput::characteristicId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (requestedIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> namesByCharacteristicId =
        dependencies.cabinCharacteristics().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MaintenanceDependencyGateway.CabinCharacteristicSnapshot::characteristicId,
                    MaintenanceDependencyGateway.CabinCharacteristicSnapshot::characteristicName,
                    (left, right) -> {
                      throw new MaintenanceDependencyException(
                          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                          "Asset-service returned duplicate cabin characteristic identities");
                    },
                    LinkedHashMap::new));
    Set<UUID> missing = new LinkedHashSet<>(requestedIds);
    missing.removeAll(namesByCharacteristicId.keySet());
    if (!missing.isEmpty()) {
      throw commandSupport.invalid(
          "Catalog material references an unavailable cabin characteristic: "
              + missing.iterator().next());
    }
    Map<UUID, String> result = new LinkedHashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (node.characteristicId() != null) {
        result.put(
            node.id(),
            namesByCharacteristicId.get(node.characteristicId()));
      }
    }
    return Map.copyOf(result);
  }

  protected Map<UUID, String> catalogCharacteristicNames(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .filter(node -> node.getCharacteristicId() != null)
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                CatalogNode::getId, CatalogNode::getCharacteristicName));
  }

  protected CatalogValidation validateCatalog(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {
    if (nodes == null || links == null) throw new IllegalArgumentException("Catalog arrays are required");
    Set<UUID> ids = new HashSet<>();
    Set<String> workAndMaterialNames = new HashSet<>();
    Map<UUID, CatalogNodeInput> nodesById = new HashMap<>();
    Map<UUID, FurnitureEquipmentReference> equipmentSnapshots = new HashMap<>();
    Map<UUID, List<UUID>> parents = new HashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (!ids.add(node.id())) throw commandSupport.invalid("Duplicate catalog node ID");
      if (node.nodeType() == CatalogNodeType.WORK
          || node.nodeType() == CatalogNodeType.MATERIAL) {
        String identity =
            node.nodeType().name()
                + ":"
                + Objects.toString(node.parentNodeId(), "ROOT")
                + ":"
                + Objects.toString(node.name(), "")
                    .trim()
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
        if (!workAndMaterialNames.add(identity)) {
          throw commandSupport.invalid(
              "Duplicate work/material name under the same catalog parent: "
                  + node.name());
        }
      }
      nodesById.put(node.id(), node);
      if (node.nodeType() == CatalogNodeType.WORK
          && (node.durationMinutes() == null || node.durationMinutes() < 1)) {
        throw commandSupport.invalid("Catalog WORK durationMinutes must be positive");
      }
      if (Boolean.TRUE.equals(node.furnitureCategory())
          && node.nodeType() != CatalogNodeType.CATEGORY) {
        throw commandSupport.invalid("Only a catalog category can mark a furniture tree");
      }
      if (node.furnitureEquipment() != null && node.nodeType() != CatalogNodeType.MATERIAL) {
        throw commandSupport.invalid("Only a material can reference furniture equipment");
      }
      if (Boolean.TRUE.equals(node.forcesCapitalRepair())
          && node.nodeType() != CatalogNodeType.WORK) {
        throw commandSupport.invalid("Only a work can force capital repair");
      }
      if (node.characteristicId() != null
          && node.nodeType() != CatalogNodeType.MATERIAL) {
        throw commandSupport.invalid("Only a material can reference a cabin characteristic");
      }
      if (node.furnitureEquipment() != null) {
        FurnitureEquipmentReference previous = equipmentSnapshots.putIfAbsent(
            node.furnitureEquipment().equipmentId(), node.furnitureEquipment());
        if (previous != null
            && !previous.equipmentName().equals(node.furnitureEquipment().equipmentName())) {
          throw commandSupport.invalid("One furniture equipment ID must use one canonical name");
        }
      }
    }
    for (CatalogNodeInput node : nodes) {
      if (node.parentNodeId() == null) continue;
      if (node.id().equals(node.parentNodeId())) throw commandSupport.invalid("Catalog node cannot parent itself");
      if (!ids.contains(node.parentNodeId())) throw commandSupport.invalid("Catalog parent node is missing");
      parents.computeIfAbsent(node.id(), ignored -> new ArrayList<>()).add(node.parentNodeId());
    }
    if (containsCycle(ids, parents)) throw commandSupport.invalid("Catalog parent hierarchy contains a cycle");
    Set<UUID> furnitureRoots = nodes.stream()
        .filter(MaintenanceCatalogSupport::marksFurnitureTree)
        .map(CatalogNodeInput::id)
        .collect(java.util.stream.Collectors.toSet());
    for (CatalogNodeInput node : nodes) {
      if (node.furnitureEquipment() != null
          && !estimateSupport.belongsToFurnitureTree(node.id(), furnitureRoots, nodesById)) {
        throw commandSupport.invalid("Furniture equipment can only be linked inside a furniture category");
      }
    }
    Set<UUID> linkIds = new HashSet<>();
    Set<String> typedEdges = new HashSet<>();
    Map<UUID, List<UUID>> dependency = new HashMap<>();
    for (CatalogLinkInput link : links) {
      if (!linkIds.add(link.id())) throw commandSupport.invalid("Duplicate catalog link ID");
      if (!ids.contains(link.fromNodeId()) || !ids.contains(link.toNodeId())) {
        throw commandSupport.invalid("Catalog link endpoint is missing");
      }
      if (link.fromNodeId().equals(link.toNodeId())) throw commandSupport.invalid("Catalog link cannot self-reference");
      if ((link.sourceAnchor() == null) != (link.targetAnchor() == null)) {
        throw commandSupport.invalid("Catalog link anchors must be both absent or both present");
      }
      String edge = link.fromNodeId() + ":" + link.toNodeId() + ":" + link.linkType().name();
      if (!typedEdges.add(edge)) throw commandSupport.invalid("Duplicate typed catalog edge");
      if (link.linkType() == CatalogLinkType.DEPENDENCY) {
        dependency.computeIfAbsent(link.fromNodeId(), ignored -> new ArrayList<>()).add(link.toNodeId());
      }
    }
    if (containsCycle(ids, dependency)) throw commandSupport.invalid("Catalog dependency graph contains a cycle");
    return new CatalogValidation(true);
  }

  protected void validateFurnitureCatalogForActivation(UUID catalogVersionId) {
    List<CatalogNode> nodes =
        catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId);
    Map<UUID, CatalogNode> nodesById = nodes.stream().collect(
        java.util.stream.Collectors.toMap(CatalogNode::getId, value -> value));
    boolean missingEquipment = nodes.stream().anyMatch(node ->
        node.isActive()
            && node.isIncludeInEstimate()
            && "MATERIAL".equals(node.getNodeType())
            && estimateSupport.belongsToFurnitureTree(node, nodesById)
            && node.getFurnitureEquipmentId() == null);
    if (missingEquipment) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Every active furniture material must be linked to additional equipment before activation");
    }
    Map<UUID, CatalogNode> equipmentSnapshots = new HashMap<>();
    for (CatalogNode node : nodes) {
      if (node.getFurnitureEquipmentId() == null) continue;
      CatalogNode previous = equipmentSnapshots.putIfAbsent(node.getFurnitureEquipmentId(), node);
      if (previous != null
          && !previous.getFurnitureEquipmentName().equals(node.getFurnitureEquipmentName())) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_VALIDATION_FAILED",
            "One furniture equipment ID must use one canonical name");
      }
    }
  }

  protected Map<UUID, RoutingSnapshot> canonicalCatalogRouting(
      List<CatalogNodeInput> nodes) {
    commandSupport.requireNoCallerTransaction("resolve canonical catalog routing");
    Map<UUID, CatalogRoutingInput> unique = new LinkedHashMap<>();
    for (CatalogNodeInput node : nodes) {
      CatalogRoutingInput routing = directRouting(node);
      if (routing == null) continue;
      CatalogRoutingInput previous = unique.putIfAbsent(routing.queueId(), routing);
      if (previous != null && !previous.equals(routing)) {
        throw commandSupport.invalid("One queue ID has conflicting catalog routing types");
      }
    }
    if (unique.isEmpty()) return Map.of();
    List<MaintenanceDependencyGateway.CatalogRoutingQueueRequirement> requirements =
        unique.values().stream()
            .map(routing -> new MaintenanceDependencyGateway.CatalogRoutingQueueRequirement(
                routing.queueId(), routing.queueType().trim().toUpperCase(Locale.ROOT)))
            .toList();
    MaintenanceDependencyGateway.CatalogRoutingPreflight preflight =
        dependencies.preflightCatalogRouting(requirements);
    if (preflight == null) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board omitted canonical catalog routing truth");
    }
    Map<UUID, MaintenanceDependencyGateway.CatalogRoutingQueueRequirement> byId =
        requirements.stream().collect(java.util.stream.Collectors.toMap(
            MaintenanceDependencyGateway.CatalogRoutingQueueRequirement::queueDefinitionId,
            value -> value));
    if (!preflight.ready()) {
      List<FieldViolation> violations = new ArrayList<>();
      for (UUID missingQueueId : preflight.missingQueueDefinitionIds()) {
        if (!byId.containsKey(missingQueueId)) continue;
        violations.add(new FieldViolation(
            "routing." + missingQueueId,
            "MAINTENANCE_ROUTING_QUEUE_MISSING",
            "Required task-board queue " + missingQueueId + " is missing"));
      }
      for (MaintenanceDependencyGateway.CatalogRoutingMismatch mismatch :
          preflight.mismatches()) {
        if (!byId.containsKey(mismatch.queueDefinitionId())) continue;
        violations.add(new FieldViolation(
            "routing." + mismatch.queueDefinitionId(),
            "MAINTENANCE_ROUTING_QUEUE_MISMATCH",
            "Task-board queue definition " + mismatch.queueDefinitionId() + " differs in "
                + String.join(", ", mismatch.fields())));
      }
      if (violations.isEmpty()) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board returned an unexplained routing preflight failure");
      }
      throw new MaintenanceCatalogValidationException(violations);
    }
    Map<UUID, RoutingSnapshot> byQueueId = new LinkedHashMap<>();
    for (MaintenanceDependencyGateway.QueueDefinitionSnapshot queue :
        preflight.resolvedDefinitions()) {
      MaintenanceDependencyGateway.CatalogRoutingQueueRequirement requirement =
          byId.get(queue.queueDefinitionId());
      if (requirement == null
          || !requirement.type().equals(queue.type().trim().toUpperCase(Locale.ROOT))) {
        throw new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Task-board returned inconsistent canonical routing truth");
      }
      byQueueId.put(
          queue.queueDefinitionId(),
          new RoutingSnapshot(queue.queueDefinitionId(), queue.name(), queue.type()));
    }
    if (!byQueueId.keySet().containsAll(unique.keySet())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board omitted a canonical routing snapshot");
    }
    Map<UUID, RoutingSnapshot> result = new LinkedHashMap<>();
    for (CatalogNodeInput node : nodes) {
      if (node.routing() != null) result.put(node.id(), byQueueId.get(node.routing().queueId()));
    }
    return Map.copyOf(result);
  }

  protected Map<UUID, RoutingSnapshot> canonicalRoutingFromCatalog(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .filter(node -> node.getRoutingQueueId() != null)
        .collect(java.util.stream.Collectors.toMap(
            CatalogNode::getId,
            node -> new RoutingSnapshot(
                node.getRoutingQueueId(), node.getRoutingQueueName(), node.getRoutingQueueType()),
            (first, ignored) -> first,
            LinkedHashMap::new));
  }

  protected void validateCatalogRoutingForActivation(UUID catalogVersionId) {
    validateCatalogRoutingForActivation(
        catalogNodeInputs(catalogVersionId), catalogLinkInputs(catalogVersionId));
  }

  protected void validateCatalogRoutingForActivation(
      List<CatalogNodeInput> nodes, List<CatalogLinkInput> links) {
    Map<UUID, CatalogNodeInput> nodesById =
        nodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNodeInput::id, value -> value));
    Map<UUID, List<UUID>> incomingLinks = CatalogRoutingResolver.incomingInputs(links);
    List<FieldViolation> violations = new ArrayList<>();
    for (CatalogNodeInput node : nodes) {
      CatalogRoutingInput direct = directRouting(node);
      if (direct != null
          && !("REPAIR".equals(direct.queueType())
              || "HOLDING".equals(direct.queueType()))) {
        violations.add(
            new FieldViolation(
                "nodes." + node.id() + ".routing",
                "MAINTENANCE_ROUTING_QUEUE_TYPE_UNSUPPORTED",
                "Catalog categories may route only to REPAIR or HOLDING queues"));
      }
      if (node.active()
          && node.includeInEstimate()
          && node.nodeType() == CatalogNodeType.WORK
          && CatalogRoutingResolver.resolve(
                  node.id(),
                  nodesById,
                  CatalogNodeInput::parentNodeId,
                  MaintenanceCatalogSupport::directRouteValue,
                  incomingLinks)
              == null) {
        violations.add(
            new FieldViolation(
                "nodes." + node.id() + ".routing",
                "MAINTENANCE_ROUTING_QUEUE_REQUIRED",
                "Every active estimate work must inherit or define a task-board queue"));
      }
    }
    if (!violations.isEmpty()) {
      throw new MaintenanceCatalogValidationException(violations);
    }
  }

  protected static RoutingSnapshot directRouting(CatalogNode node) {
    return node.getRoutingQueueId() == null
        ? null
        : new RoutingSnapshot(
            node.getRoutingQueueId(),
            node.getRoutingQueueName(),
            node.getRoutingQueueType());
  }

  protected static CatalogRoutingInput directRouting(CatalogNodeInput node) {
    return node.routing();
  }

  protected static CatalogRoutingResolver.Route directRouteValue(CatalogNode node) {
    return CatalogRoutingResolver.from(directRouting(node));
  }

  protected static CatalogRoutingResolver.Route directRouteValue(CatalogNodeInput node) {
    return CatalogRoutingResolver.from(directRouting(node));
  }

  protected void enqueueCatalogRouting(
      CatalogVersion active, CatalogVersion superseded) {
    List<CatalogNode> activeRouted = routedCatalogNodes(active.getId());
    List<UUID> registrationKeys = new ArrayList<>();
    for (CatalogNode node : activeRouted) {
      UUID registrationKey = catalogRegistrationKey(active.getId(), node);
      registrationKeys.add(registrationKey);
      reconciliations.enqueueCatalogPosition(
          "REGISTER_CATALOG_POSITION",
          registrationKey,
          active.getId(),
          node.getId(),
          node.getRoutingQueueId(),
          catalogExternalReference(active.getId(), node.getId()),
          List.of());
    }
    if (superseded == null) return;
    List<UUID> cleanupPredecessors = new ArrayList<>(registrationKeys);
    cleanupPredecessors.addAll(reconciliations.catalogRegistrationKeys(superseded.getId()));
    cleanupPredecessors = cleanupPredecessors.stream().distinct().toList();
    for (CatalogNode node : routedCatalogNodes(superseded.getId())) {
      reconciliations.enqueueCatalogPosition(
          "DELETE_CATALOG_POSITION",
          MaintenanceCommandSupport.stableOperationKey(
              "delete-catalog-position:" + node.getId() + ":" + active.getId(),
              superseded.getId(),
              0),
          superseded.getId(),
          node.getId(),
          node.getRoutingQueueId(),
          catalogExternalReference(superseded.getId(), node.getId()),
          cleanupPredecessors);
    }
  }

  protected void enqueueCatalogRoutingChange(
      CatalogVersion active,
      List<CatalogNodeInput> previousNodes,
      List<CatalogNodeInput> currentNodes) {
    Map<UUID, CatalogNodeInput> previousById =
        previousNodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNodeInput::id, value -> value));
    Map<UUID, CatalogNodeInput> currentById =
        currentNodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNodeInput::id, value -> value));
    Set<UUID> nodeIds = new java.util.TreeSet<>(Comparator.comparing(UUID::toString));
    nodeIds.addAll(previousById.keySet());
    nodeIds.addAll(currentById.keySet());
    for (UUID nodeId : nodeIds) {
      CatalogNodeInput previous = previousById.get(nodeId);
      CatalogNodeInput current = currentById.get(nodeId);
      CatalogRoutingInput previousRouting = previous == null ? null : previous.routing();
      CatalogRoutingInput currentRouting = current == null ? null : current.routing();
      if (java.util.Objects.equals(previousRouting, currentRouting)) continue;
      List<UUID> predecessors = new ArrayList<>();
      if (previousRouting != null) {
        UUID deleteKey =
            MaintenanceCommandSupport.stableOperationKey(
                "delete-catalog-position-change:"
                    + nodeId
                    + ":"
                    + previousRouting.queueId()
                    + ":"
                    + active.getVersion(),
                active.getId(),
                0);
        reconciliations.enqueueCatalogPosition(
            "DELETE_CATALOG_POSITION",
            deleteKey,
            active.getId(),
            nodeId,
            previousRouting.queueId(),
            catalogExternalReference(active.getId(), nodeId),
            List.of());
        predecessors.add(deleteKey);
      }
      if (currentRouting != null) {
        UUID registerKey =
            MaintenanceCommandSupport.stableOperationKey(
                "register-catalog-position-change:"
                    + nodeId
                    + ":"
                    + currentRouting.queueId()
                    + ":"
                    + active.getVersion(),
                active.getId(),
                0);
        reconciliations.enqueueCatalogPosition(
            "REGISTER_CATALOG_POSITION",
            registerKey,
            active.getId(),
            nodeId,
            currentRouting.queueId(),
            catalogExternalReference(active.getId(), nodeId),
            predecessors);
      }
    }
  }

  protected List<CatalogNode> routedCatalogNodes(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .filter(node -> node.getRoutingQueueId() != null)
        .toList();
  }

  protected static UUID catalogRegistrationKey(UUID catalogVersionId, CatalogNode node) {
    return MaintenanceCommandSupport.stableOperationKey(
        "register-catalog-position:" + node.getId() + ":" + node.getRoutingQueueId(),
        catalogVersionId,
        0);
  }

  protected static String catalogExternalReference(UUID catalogVersionId, UUID catalogNodeId) {
    return "catalog:" + catalogVersionId + ":" + catalogNodeId;
  }

  protected static boolean marksFurnitureTree(CatalogNodeInput node) {
    return node.nodeType() == CatalogNodeType.CATEGORY
        && Boolean.TRUE.equals(node.furnitureCategory());
  }

  protected static boolean containsCycle(Set<UUID> ids, Map<UUID, List<UUID>> edges) {
    Set<UUID> visiting = new HashSet<>();
    Set<UUID> visited = new HashSet<>();
    for (UUID id : ids) if (visit(id, edges, visiting, visited)) return true;
    return false;
  }

  protected static boolean visit(
      UUID id, Map<UUID, List<UUID>> edges, Set<UUID> visiting, Set<UUID> visited) {
    if (visited.contains(id)) return false;
    if (!visiting.add(id)) return true;
    for (UUID target : edges.getOrDefault(id, List.of())) {
      if (visit(target, edges, visiting, visited)) return true;
    }
    visiting.remove(id);
    visited.add(id);
    return false;
  }

  protected Map<String, Object> forkCatalogReport(
      CatalogVersion source,
      String sourceSnapshotSha256,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links,
      CatalogValidation validation) {
    Map<String, Object> report = baseCatalogReport(nodes, links, validation);
    report.put("source", "CATALOG_BUILDER_FORK");
    report.put("sourceCatalogVersionId", source.getId().toString());
    report.put("sourceCatalogVersion", source.getVersion());
    report.put("sourceCatalogLifecycle", source.getState().name());
    report.put("sourceSnapshotSha256", sourceSnapshotSha256);
    report.put("sourceNodeCount", nodes.size());
    report.put("sourceLinkCount", links.size());
    report.put("sourceMaterialCount", materialCount(nodes));
    report.put("reportSha256", commandSupport.hash(report));
    return report;
  }

  protected Map<String, Object> baseCatalogReport(
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links,
      CatalogValidation validation) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("valid", true);
    report.put("errorCount", 0);
    report.put("warningCount", 0);
    report.put("contentSha256", commandSupport.hash(new CatalogContent(nodes, links)));
    report.put("nodeCount", nodes.size());
    report.put("linkCount", links.size());
    report.put("materialCount", materialCount(nodes));
    report.put("dependencyAcyclic", validation.dependencyAcyclic());
    return report;
  }

  protected void requireMatchingFork(
      CatalogVersion existing,
      CatalogForkSnapshot sourceSnapshot,
      String sourceSnapshotSha256) {
    if (existing.getState() != CatalogVersionState.DRAFT) {
      throw new MaintenanceConflictException(
          "MAINTENANCE_STATE_CONFLICT",
          "The source snapshot is already bound to a published catalog version");
    }
    List<CatalogNodeInput> nodes = catalogNodeInputs(existing.getId());
    List<CatalogLinkInput> links = catalogLinkInputs(existing.getId());
    if (existing.getNodeCount() != nodes.size()
        || existing.getLinkCount() != links.size()) {
      throw catalogSourceConflict();
    }
    CatalogForkSnapshot actual = new CatalogForkSnapshot(
        sourceSnapshot.sourceCatalogVersionId(),
        sourceSnapshot.sourceCatalogVersion(),
        sourceSnapshot.sourceLifecycle(),
        nodes,
        links);
    Map<String, Object> report = commandSupport.jsonMap(existing.getValidationReport());
    if (!sourceSnapshotSha256.equals(existing.getSourceSha256())
        || !sourceSnapshotSha256.equals(commandSupport.hash(actual))
        || !sourceSnapshotSha256.equals(report.get("sourceSnapshotSha256"))
        || !sourceSnapshot.sourceCatalogVersionId().toString()
            .equals(report.get("sourceCatalogVersionId"))
        || !numberEquals(report.get("sourceCatalogVersion"), sourceSnapshot.sourceCatalogVersion())
        || !sourceSnapshot.sourceLifecycle().name().equals(report.get("sourceCatalogLifecycle"))) {
      throw catalogSourceConflict();
    }
  }

  protected static boolean numberEquals(Object value, long expected) {
    return value instanceof Number number && number.longValue() == expected;
  }

  protected static int materialCount(List<CatalogNodeInput> nodes) {
    return Math.toIntExact(
        nodes.stream()
            .filter(node -> node.nodeType() == CatalogNodeType.MATERIAL)
            .count());
  }

  protected static MaintenanceConflictException catalogSourceConflict() {
    return new MaintenanceConflictException(
        "MAINTENANCE_STATE_CONFLICT",
        "Existing catalog source does not match the requested catalog snapshot");
  }

  protected List<CatalogNodeInput> catalogNodeInputs(UUID catalogVersionId) {
    return catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(catalogVersionId).stream()
        .map(this::catalogNodeInput)
        .toList();
  }

  protected CatalogNodeInput catalogNodeInput(CatalogNode value) {
    return new CatalogNodeInput(
        value.getId(),
        CatalogNodeType.valueOf(value.getNodeType()),
        value.getName(),
        value.isActive(),
        value.getParentNodeId(),
        value.isFurnitureCategory(),
        value.getFurnitureEquipmentId() == null
            ? null
            : catalogFurnitureMapper.toReference(value),
        value.getUnit(),
        commandSupport.money(value.getPriceMinor()),
        value.getDurationMinutes(),
        value.isIncludeInEstimate(),
        value.isCommonItem(),
        value.isShowInMainMenu(),
        value.getCanvasX(),
        value.getCanvasY(),
        value.getRoutingQueueId() == null
            ? null
            : new CatalogRoutingInput(
                value.getRoutingQueueId(),
                value.getRoutingQueueType()),
        value.getComment(),
        value.getDisplayColor(),
        value.isForcesCapitalRepair(),
        value.getCharacteristicId());
  }

  protected List<CatalogLinkInput> catalogLinkInputs(UUID catalogVersionId) {
    return catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(catalogVersionId).stream()
        .map(link -> new CatalogLinkInput(
            link.getId(),
            link.getSourceNodeId(),
            link.getTargetNodeId(),
            CatalogLinkType.valueOf(link.getLinkType()),
            link.getSourceAnchor() == null
                ? null
                : CatalogLinkAnchor.valueOf(link.getSourceAnchor()),
            link.getTargetAnchor() == null
                ? null
                : CatalogLinkAnchor.valueOf(link.getTargetAnchor()),
            link.getSortOrder()))
        .toList();
  }

  protected void saveCatalog(
      UUID versionId,
      List<CatalogNodeInput> nodes,
      List<CatalogLinkInput> links,
      Map<UUID, RoutingSnapshot> canonicalRouting,
      Map<UUID, String> characteristicNames) {
    catalogNodes.saveAllAndFlush(nodes.stream().map(node -> new CatalogNode(
        node.id(), versionId, node.nodeType().name(), node.name(),
        node.active(), node.parentNodeId(), marksFurnitureTree(node),
        node.furnitureEquipment() == null ? null : node.furnitureEquipment().equipmentId(),
        node.furnitureEquipment() == null ? null : node.furnitureEquipment().equipmentName(),
        node.unit(),
        node.unitPrice() == null ? null : commandSupport.moneyToMinor(node.unitPrice()), node.durationMinutes(),
        node.includeInEstimate(), node.commonItem(), node.showInMainMenu(),
        node.canvasX(), node.canvasY(),
        node.routing() == null ? null : node.routing().queueId(),
        node.routing() == null ? null : requiredCanonicalRouting(canonicalRouting, node).queueName(),
        node.routing() == null ? null : requiredCanonicalRouting(canonicalRouting, node).queueType(),
        node.comment(),
        node.displayColor(),
        node.forcesCapitalRepair(),
        node.characteristicId(),
        node.characteristicId() == null
            ? null
            : requiredCharacteristicName(characteristicNames, node))).toList());
    catalogLinks.saveAllAndFlush(links.stream().map(link -> new CatalogLink(
        link.id(), versionId, link.fromNodeId(), link.toNodeId(), link.linkType().name(),
        link.sourceAnchor() == null ? null : link.sourceAnchor().name(),
        link.targetAnchor() == null ? null : link.targetAnchor().name(),
        link.sortOrder())).toList());
  }

  protected static RoutingSnapshot requiredCanonicalRouting(
      Map<UUID, RoutingSnapshot> canonicalRouting, CatalogNodeInput node) {
    RoutingSnapshot routing = canonicalRouting.get(node.id());
    if (routing == null || !routing.queueId().equals(node.routing().queueId())) {
      throw new IllegalStateException("Catalog routing was not canonicalized by task-board");
    }
    return routing;
  }

  protected static String requiredCharacteristicName(
      Map<UUID, String> characteristicNames, CatalogNodeInput node) {
    String name = characteristicNames.get(node.id());
    if (name == null || name.isBlank()) {
      throw new IllegalStateException(
          "Catalog characteristic identity was not resolved by asset-service");
    }
    return name;
  }
}
