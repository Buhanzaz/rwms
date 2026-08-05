package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Resolves every furniture material through durable node identity before catalog mutation. */
@Service
public class FurnitureEquipmentLinkResolver {
  private final FurnitureEquipmentLinkStore links;
  private final FurnitureEquipmentLinkProcessor processor;

  public FurnitureEquipmentLinkResolver(
      FurnitureEquipmentLinkStore links, FurnitureEquipmentLinkProcessor processor) {
    this.links = links;
    this.processor = processor;
  }

  public List<CatalogNodeInput> resolve(
      UUID catalogVersionId,
      UUID warehouseId,
      long catalogExpectedVersion,
      List<CatalogNodeInput> nodes) {
    Map<UUID, CatalogNodeInput> nodesById = nodes.stream()
        .collect(Collectors.toMap(CatalogNodeInput::id, value -> value));
    Set<UUID> furnitureRoots = nodes.stream()
        .filter(FurnitureEquipmentLinkResolver::marksFurnitureTree)
        .map(CatalogNodeInput::id)
        .collect(Collectors.toSet());
    List<CatalogNodeInput> furnitureMaterials = nodes.stream()
        .filter(node -> node.nodeType() == CatalogNodeType.MATERIAL)
        .filter(node -> belongsToFurnitureTree(node.id(), furnitureRoots, nodesById))
        .sorted(Comparator.comparing(CatalogNodeInput::name).thenComparing(CatalogNodeInput::id))
        .toList();
    if (furnitureMaterials.isEmpty()) return nodes;

    links.prepareAll(
        warehouseId,
        catalogVersionId,
        catalogExpectedVersion,
        furnitureMaterials.stream()
            .map(node -> new FurnitureEquipmentLinkStore.LinkRequirement(node.id(), node.name()))
            .toList());

    Map<UUID, FurnitureEquipmentReference> resolved = new HashMap<>();
    for (CatalogNodeInput node : furnitureMaterials) {
      FurnitureEquipmentLinkStore.LinkSnapshot snapshot = links.require(node.id());
      if (!"CONFIRMED".equals(snapshot.state())) {
        if ("REVIEW_REQUIRED".equals(snapshot.state())) {
          throw new MaintenanceConflictException(
              "FURNITURE_EQUIPMENT_LINK_REVIEW_REQUIRED",
              "Furniture equipment auto-link requires administrator review");
        }
        if ("ABANDONED".equals(snapshot.state())) {
          throw new MaintenanceConflictException(
              "FURNITURE_EQUIPMENT_LINK_ABANDONED",
              "Furniture equipment auto-link was abandoned");
        }
        if (!processor.processExact(node.id())) {
          throw new MaintenanceDependencyException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "Furniture equipment auto-link is already being reconciled");
        }
        snapshot = links.require(node.id());
      }
      if (!"CONFIRMED".equals(snapshot.state())
          || snapshot.equipmentId() == null
          || snapshot.equipmentName() == null) {
        throw new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Furniture equipment auto-link has not been confirmed");
      }
      FurnitureEquipmentReference canonical =
          new FurnitureEquipmentReference(snapshot.equipmentId(), snapshot.equipmentName());
      if (node.furnitureEquipment() != null
          && !node.furnitureEquipment().equals(canonical)) {
        throw new MaintenanceConflictException(
            "FURNITURE_EQUIPMENT_REMAP_CONFLICT",
            "Furniture node UUID is already bound to another asset equipment mapping");
      }
      resolved.put(node.id(), canonical);
    }
    return nodes.stream()
        .map(node -> resolved.containsKey(node.id())
            ? withFurnitureEquipment(node, resolved.get(node.id()))
            : node)
        .toList();
  }

  private static boolean marksFurnitureTree(CatalogNodeInput node) {
    return node.nodeType() == CatalogNodeType.CATEGORY
        && Boolean.TRUE.equals(node.furnitureCategory());
  }

  private static boolean belongsToFurnitureTree(
      UUID nodeId, Set<UUID> furnitureRoots, Map<UUID, CatalogNodeInput> nodesById) {
    UUID current = nodeId;
    java.util.HashSet<UUID> visited = new java.util.HashSet<>();
    while (current != null && visited.add(current)) {
      if (furnitureRoots.contains(current)) return true;
      CatalogNodeInput node = nodesById.get(current);
      current = node == null ? null : node.parentNodeId();
    }
    return false;
  }

  private static CatalogNodeInput withFurnitureEquipment(
      CatalogNodeInput node, FurnitureEquipmentReference furnitureEquipment) {
    return new CatalogNodeInput(
        node.id(),
        node.nodeType(),
        node.name(),
        node.active(),
        node.parentNodeId(),
        node.furnitureCategory(),
        furnitureEquipment,
        node.unit(),
        node.unitPrice(),
        node.durationMinutes(),
        node.includeInEstimate(),
        node.commonItem(),
        node.showInMainMenu(),
        node.canvasX(),
        node.canvasY(),
        node.routing(),
        node.comment(),
        node.displayColor(),
        node.forcesCapitalRepair(),
        node.characteristicId());
  }
}
