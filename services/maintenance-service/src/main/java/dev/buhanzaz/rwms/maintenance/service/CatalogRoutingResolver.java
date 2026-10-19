package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogLinkInput;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogRoutingInput;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.RoutingSnapshot;
import dev.buhanzaz.rwms.maintenance.domain.CatalogLink;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resolves one catalog node's queue through the complete catalog graph.
 *
 * <p>A route may be declared on the node itself, its parent category, or any
 * graph predecessor (both link kinds are routing edges). A node is routable
 * only when all reachable declarations agree on one stable queue identity.
 * Ambiguous and cyclic graphs fail closed instead of selecting an arbitrary
 * branch.
 */
final class CatalogRoutingResolver {
  private CatalogRoutingResolver() {}

  record Route(UUID queueId, String queueName, String queueType) {
    Route {
      if (queueId == null) {
        throw new IllegalArgumentException("queueId is required");
      }
      queueType = queueType == null ? null : queueType.trim().toUpperCase(java.util.Locale.ROOT);
      queueName = queueName == null ? null : queueName.trim();
      if (queueType == null || queueType.isBlank()) {
        throw new IllegalArgumentException("queueType is required");
      }
      if (queueName != null && queueName.isBlank()) queueName = null;
    }

    String identity() {
      return queueId + ":" + queueType;
    }
  }

  static Route from(RoutingSnapshot routing) {
    return routing == null
        ? null
        : new Route(routing.queueId(), routing.queueName(), routing.queueType());
  }

  static Route from(CatalogRoutingInput routing) {
    return routing == null ? null : new Route(routing.queueId(), null, routing.queueType());
  }

  static RoutingSnapshot snapshot(Route route) {
    if (route == null || route.queueName() == null || route.queueName().isBlank()) {
      return null;
    }
    return new RoutingSnapshot(route.queueId(), route.queueName(), route.queueType());
  }

  static CatalogRoutingInput input(Route route) {
    return route == null ? null : new CatalogRoutingInput(route.queueId(), route.queueType());
  }

  static Map<UUID, List<UUID>> incoming(Collection<CatalogLink> links) {
    Map<UUID, List<IncomingLink>> grouped = new HashMap<>();
    for (CatalogLink link : links) {
      grouped.computeIfAbsent(link.getTargetNodeId(), ignored -> new ArrayList<>())
          .add(new IncomingLink(link.getSourceNodeId(), link.getSortOrder(), link.getId()));
    }
    return freezeIncoming(grouped);
  }

  static Map<UUID, List<UUID>> incomingInputs(Collection<CatalogLinkInput> links) {
    Map<UUID, List<IncomingLink>> grouped = new HashMap<>();
    for (CatalogLinkInput link : links) {
      grouped.computeIfAbsent(link.toNodeId(), ignored -> new ArrayList<>())
          .add(new IncomingLink(link.fromNodeId(), link.sortOrder(), link.id()));
    }
    return freezeIncoming(grouped);
  }

  private static Map<UUID, List<UUID>> freezeIncoming(
      Map<UUID, List<IncomingLink>> grouped) {
    Map<UUID, List<UUID>> result = new LinkedHashMap<>();
    grouped.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> {
          List<UUID> predecessors = entry.getValue().stream()
              .sorted(Comparator.comparingInt(IncomingLink::sortOrder)
                  .thenComparing(IncomingLink::id))
              .map(IncomingLink::sourceNodeId)
              .distinct()
              .toList();
          result.put(entry.getKey(), predecessors);
        });
    return Map.copyOf(result);
  }

  /**
   * Resolves a route for a node. The node's explicit route takes precedence;
   * otherwise parent and all incoming graph predecessors are traversed.
   */
  static <N> Route resolve(
      UUID nodeId,
      Map<UUID, N> nodes,
      Function<N, UUID> parentId,
      Function<N, Route> directRoute,
      Map<UUID, List<UUID>> incoming) {
    WalkResult result = walk(nodeId, nodes, parentId, directRoute, incoming, new LinkedHashSet<>());
    if (!result.valid() || result.routes().isEmpty()) return null;
    if (result.routes().size() > 1) return null;
    return result.routes().values().iterator().next();
  }

  private static <N> WalkResult walk(
      UUID nodeId,
      Map<UUID, N> nodes,
      Function<N, UUID> parentId,
      Function<N, Route> directRoute,
      Map<UUID, List<UUID>> incoming,
      Set<UUID> path) {
    if (nodeId == null || !path.add(nodeId)) return WalkResult.invalid();
    try {
      N node = nodes.get(nodeId);
      if (node == null) return WalkResult.invalid();
      Route direct = directRoute.apply(node);
      if (direct != null) {
        return WalkResult.of(direct);
      }

      LinkedHashSet<UUID> predecessors = new LinkedHashSet<>();
      UUID parent = parentId.apply(node);
      if (parent != null) predecessors.add(parent);
      predecessors.addAll(incoming.getOrDefault(nodeId, List.of()));
      if (predecessors.isEmpty()) return WalkResult.empty();

      WalkResult combined = WalkResult.empty();
      for (UUID predecessor : predecessors) {
        WalkResult branch = walk(
            predecessor, nodes, parentId, directRoute, incoming, path);
        if (!branch.valid()) return WalkResult.invalid();
        combined = combined.merge(branch);
        if (combined.routes().size() > 1) return combined;
      }
      return combined;
    } finally {
      path.remove(nodeId);
    }
  }

  private record IncomingLink(UUID sourceNodeId, int sortOrder, UUID id) {}

  private record WalkResult(boolean valid, Map<String, Route> routes) {
    static WalkResult invalid() {
      return new WalkResult(false, Map.of());
    }

    static WalkResult empty() {
      return new WalkResult(true, Map.of());
    }

    static WalkResult of(Route route) {
      return new WalkResult(true, Map.of(route.identity(), route));
    }

    WalkResult merge(WalkResult other) {
      if (!valid || !other.valid) return invalid();
      if (routes.isEmpty()) return other;
      if (other.routes.isEmpty()) return this;
      Map<String, Route> merged = new LinkedHashMap<>(routes);
      other.routes.forEach((identity, candidate) -> merged.merge(identity, candidate,
          WalkResult::preferredRoute));
      return new WalkResult(true, Map.copyOf(merged));
    }

    private static Route preferredRoute(Route left, Route right) {
      if (left.queueName() == null) return right;
      if (right.queueName() == null) return left;
      return left.queueName().compareTo(right.queueName()) <= 0 ? left : right;
    }
  }
}
