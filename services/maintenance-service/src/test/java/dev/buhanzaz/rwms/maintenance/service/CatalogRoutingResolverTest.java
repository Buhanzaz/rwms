package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogLinkType.DEPENDENCY;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogLinkType.FOLLOW_UP;
import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogLinkInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogRoutingResolverTest {
  private static final UUID CATEGORY = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID FIRST_BLOCK = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID SECOND_BLOCK = UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID WORK = UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID REPAIR_QUEUE = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_QUEUE = UUID.fromString("20000000-0000-0000-0000-000000000002");

  @Test
  void followsParentAndEveryIncomingGraphLevelForOneStableRoute() {
    Map<UUID, Node> nodes = nodes(
        node(CATEGORY, null, route(REPAIR_QUEUE, "Ремонт")),
        node(FIRST_BLOCK, CATEGORY, null),
        node(SECOND_BLOCK, null, null),
        node(WORK, null, null));
    Map<UUID, List<UUID>> incoming = CatalogRoutingResolver.incomingInputs(List.of(
        link(FIRST_BLOCK, SECOND_BLOCK, FOLLOW_UP, 10),
        link(SECOND_BLOCK, WORK, DEPENDENCY, 20)));

    CatalogRoutingResolver.Route resolved = CatalogRoutingResolver.resolve(
        WORK, nodes, Node::parentId, Node::route, incoming);

    assertThat(resolved).isNotNull();
    assertThat(resolved.queueId()).isEqualTo(REPAIR_QUEUE);
    assertThat(resolved.queueType()).isEqualTo("REPAIR");
  }

  @Test
  void rejectsConflictingGraphRoutesInsteadOfChoosingOneBranch() {
    Map<UUID, Node> nodes = nodes(
        node(FIRST_BLOCK, null, route(REPAIR_QUEUE, "Ремонт")),
        node(SECOND_BLOCK, null, route(OTHER_QUEUE, "Другая очередь")),
        node(WORK, null, null));
    Map<UUID, List<UUID>> incoming = CatalogRoutingResolver.incomingInputs(List.of(
        link(FIRST_BLOCK, WORK, FOLLOW_UP, 10),
        link(SECOND_BLOCK, WORK, DEPENDENCY, 20)));

    assertThat(CatalogRoutingResolver.resolve(
        WORK, nodes, Node::parentId, Node::route, incoming)).isNull();
  }

  @Test
  void rejectsCyclesWithoutADeclaredRoute() {
    Map<UUID, Node> nodes = nodes(
        node(FIRST_BLOCK, null, null),
        node(SECOND_BLOCK, null, null));
    Map<UUID, List<UUID>> incoming = CatalogRoutingResolver.incomingInputs(List.of(
        link(FIRST_BLOCK, SECOND_BLOCK, FOLLOW_UP, 10),
        link(SECOND_BLOCK, FIRST_BLOCK, FOLLOW_UP, 20)));

    assertThat(CatalogRoutingResolver.resolve(
        FIRST_BLOCK, nodes, Node::parentId, Node::route, incoming)).isNull();
  }

  private static Map<UUID, Node> nodes(Node... values) {
    Map<UUID, Node> result = new LinkedHashMap<>();
    for (Node value : values) result.put(value.id(), value);
    return result;
  }

  private static Node node(UUID id, UUID parentId, CatalogRoutingResolver.Route route) {
    return new Node(id, parentId, route);
  }

  private static CatalogRoutingResolver.Route route(UUID id, String name) {
    return new CatalogRoutingResolver.Route(id, name, "REPAIR");
  }

  private static CatalogLinkInput link(
      UUID from, UUID to,
      dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogLinkType type,
      int sortOrder) {
    return new CatalogLinkInput(
        UUID.randomUUID(), from, to, type, null, null, sortOrder);
  }

  private record Node(UUID id, UUID parentId, CatalogRoutingResolver.Route route) {}
}
