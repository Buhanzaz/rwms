package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.FurnitureLossIntentState;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.InventoryStartOperation;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway.WarehouseLifecycleReadinessWork;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway.WarehouseLifecycleReadinessWorkPage;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureLossIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySourceAttachmentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStartOperationRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the inventory-owned part of warehouse lifecycle without relying on an ephemeral event.
 * The warehouse worklist is the durable trigger; a confirmation is emitted only after all local
 * operations and cross-service effects for that warehouse are terminal.
 */
@Component
public class InventoryWarehouseLifecycleReconciler {
  private static final Logger log =
      LoggerFactory.getLogger(InventoryWarehouseLifecycleReconciler.class);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES_PER_RUN = 100;
  private static final int LOCAL_PAGE_SIZE = 200;
  private static final int MAX_LOCAL_PAGES = 10_000;
  private final InventoryDependencyGateway dependencies;
  private final InventorySessionRepository sessions;
  private final InventoryStartOperationRepository startOperations;
  private final InventoryPublicationIntentRepository publications;
  private final InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
  private final InventoryFurnitureLossIntentRepository furnitureLosses;
  private final InventoryFindingRepository findings;
  private final InventorySourceAttachmentRepository sourceAttachments;
  private final AtomicBoolean running = new AtomicBoolean();

  public InventoryWarehouseLifecycleReconciler(
      InventoryDependencyGateway dependencies,
      InventorySessionRepository sessions,
      InventoryStartOperationRepository startOperations,
      InventoryPublicationIntentRepository publications,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryFurnitureLossIntentRepository furnitureLosses,
      InventoryFindingRepository findings,
      InventorySourceAttachmentRepository sourceAttachments) {
    this.dependencies = dependencies;
    this.sessions = sessions;
    this.startOperations = startOperations;
    this.publications = publications;
    this.furnitureReconciliations = furnitureReconciliations;
    this.furnitureLosses = furnitureLosses;
    this.findings = findings;
    this.sourceAttachments = sourceAttachments;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reconcileAtStartup() {
    reconcile();
  }

  @Scheduled(
      initialDelayString = "${rwms.inventory.warehouse-lifecycle.initial-delay:5s}",
      fixedDelayString = "${rwms.inventory.warehouse-lifecycle.reconciliation-delay:30s}")
  public void reconcile() {
    if (!dependencies.productionReady() || !running.compareAndSet(false, true)) {
      return;
    }
    try {
      reconcilePages();
    } catch (RuntimeException failure) {
      log.warn("Inventory warehouse lifecycle reconciliation failed", failure);
    } finally {
      running.set(false);
    }
  }

  private void reconcilePages() {
    List<WarehouseLifecycleReadinessWork> work = loadWork();
    if (work.isEmpty()) return;
    Set<UUID> pendingStartWarehouses =
        pendingStartOperationWarehouses(
            work.stream().map(WarehouseLifecycleReadinessWork::warehouseId).collect(
                java.util.stream.Collectors.toUnmodifiableSet()));
    for (WarehouseLifecycleReadinessWork item : work) {
      confirmWhenReady(item, pendingStartWarehouses.contains(item.warehouseId()));
    }
  }

  private List<WarehouseLifecycleReadinessWork> loadWork() {
    UUID after = null;
    List<WarehouseLifecycleReadinessWork> work = new ArrayList<>();
    Set<UUID> seenCursors = new HashSet<>();
    for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_RUN; pageNumber++) {
      WarehouseLifecycleReadinessWorkPage page =
          dependencies.warehouseLifecycleReadinessWork(after, PAGE_SIZE);
      work.addAll(page.items());
      UUID next = page.nextAfter();
      if (next == null) {
        return List.copyOf(work);
      }
      if (!seenCursors.add(next)) {
        throw new IllegalStateException("Warehouse readiness worklist cursor repeated");
      }
      after = next;
    }
    log.warn(
        "Inventory warehouse lifecycle reconciliation reached the per-run page limit {}",
        MAX_PAGES_PER_RUN);
    return List.copyOf(work);
  }

  private void confirmWhenReady(
      WarehouseLifecycleReadinessWork item, boolean hasPendingStartOperation) {
    if (hasPendingStartOperation || hasLocalBlockers(item.warehouseId())) {
      return;
    }
    try {
      dependencies.confirmWarehouseLifecycleReadiness(
          item.warehouseId(), item.warehouseVersion());
    } catch (InventoryException conflictOrDependencyFailure) {
      if (conflictOrDependencyFailure.status() == HttpStatus.CONFLICT) {
        // A metadata/lifecycle race is resolved by rereading the durable worklist next cycle.
        log.debug(
            "Inventory readiness confirmation fence changed for warehouse {}",
            item.warehouseId(),
            conflictOrDependencyFailure);
        return;
      }
      throw conflictOrDependencyFailure;
    }
  }

  boolean hasLocalBlockers(UUID warehouseId) {
    if (sessions.findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE).isPresent()) {
      return true;
    }
    for (int pageNumber = 0; pageNumber < MAX_LOCAL_PAGES; pageNumber++) {
      Page<InventorySession> page =
          sessions.findByWarehouseId(warehouseId, page(pageNumber, "id"));
      for (var session : page) {
        UUID inventoryId = session.getId();
        if (publications.findAllByInventoryIdOrderByFindingId(inventoryId).stream()
            .anyMatch(
                intent ->
                    !Set.of(
                            PublicationState.NOT_REQUIRED,
                            PublicationState.SUCCEEDED,
                            PublicationState.CLOSED_BLOCKED)
                        .contains(intent.getState()))) {
          return true;
        }
        if (furnitureReconciliations
            .findById(inventoryId)
            .filter(intent -> intent.getState() != FurnitureReconciliationState.SUCCEEDED)
            .isPresent()) {
          return true;
        }
        if (furnitureLosses.findAllByInventoryIdOrderByFindingIdAsc(inventoryId).stream()
            .anyMatch(intent -> intent.getState() != FurnitureLossIntentState.SUCCEEDED)) {
          return true;
        }
        if (findings.findAllByInventoryIdOrderById(inventoryId).stream()
            .map(
                finding ->
                    sourceAttachments.findByInventoryIdAndFindingId(
                        inventoryId, finding.getId()))
            .flatMap(java.util.Optional::stream)
            .anyMatch(attachment -> !attachment.isAttached())) {
          return true;
        }
      }
      if (!page.hasNext()) return false;
      if (pageNumber == MAX_LOCAL_PAGES - 1) return true;
    }
    return true;
  }

  private Set<UUID> pendingStartOperationWarehouses(Set<UUID> targetWarehouseIds) {
    Set<UUID> blocked = new HashSet<>();
    for (int pageNumber = 0; pageNumber < MAX_LOCAL_PAGES; pageNumber++) {
      Page<InventoryStartOperation> page =
          startOperations.findAll(page(pageNumber, "operationId"));
      page.stream()
          .filter(
              operation ->
                  targetWarehouseIds.contains(operation.getWarehouseId())
                      && !Set.of("RELEASED", "FAILED").contains(operation.getState()))
          .map(InventoryStartOperation::getWarehouseId)
          .forEach(blocked::add);
      if (blocked.size() == targetWarehouseIds.size() || !page.hasNext()) {
        return Set.copyOf(blocked);
      }
    }
    // The scan cap is a consistency fence: unknown local work blocks all targeted warehouses.
    return Set.copyOf(targetWarehouseIds);
  }

  private static PageRequest page(int pageNumber, String idProperty) {
    return PageRequest.of(pageNumber, LOCAL_PAGE_SIZE, Sort.by(idProperty).ascending());
  }
}
