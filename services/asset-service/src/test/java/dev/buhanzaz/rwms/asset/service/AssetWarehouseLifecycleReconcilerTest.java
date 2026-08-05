package dev.buhanzaz.rwms.asset.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseLifecycleReadinessWork;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseLifecycleReadinessWorkPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AssetWarehouseLifecycleReconcilerTest {
  @Test
  void sealsTheLocalFenceOnlyAfterRemoteReadinessConfirmation() {
    WarehouseRegistryClient warehouses = mock(WarehouseRegistryClient.class);
    AssetWarehouseLifecycleStore lifecycle = mock(AssetWarehouseLifecycleStore.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AssetWarehouseLifecycleReconciler reconciler =
        new AssetWarehouseLifecycleReconciler(warehouses, lifecycle, jdbc);
    UUID warehouseId = UUID.randomUUID();

    when(warehouses.lifecycleIntegrationEnabled()).thenReturn(true);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(warehouses.lifecycleReadinessWork(null, 100))
        .thenReturn(
            new WarehouseLifecycleReadinessWorkPage(
                List.of(new WarehouseLifecycleReadinessWork(warehouseId, 7L, "DRAINING")), null));
    when(lifecycle.beginReadiness(warehouseId, 7L))
        .thenReturn(new AssetWarehouseLifecycleStore.ReadinessAttempt(warehouseId, 7L, false, true));

    reconciler.reconcile();

    var order = inOrder(lifecycle, warehouses);
    order.verify(lifecycle).beginReadiness(warehouseId, 7L);
    order.verify(warehouses).confirmLifecycleReadiness(warehouseId, 7L);
    order.verify(lifecycle).sealReadiness(warehouseId, 7L);
  }

  @Test
  void doesNotConfirmWhenTheLocalFenceCannotBeStartedBecauseWorkIsStillLive() {
    WarehouseRegistryClient warehouses = mock(WarehouseRegistryClient.class);
    AssetWarehouseLifecycleStore lifecycle = mock(AssetWarehouseLifecycleStore.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AssetWarehouseLifecycleReconciler reconciler =
        new AssetWarehouseLifecycleReconciler(warehouses, lifecycle, jdbc);
    UUID warehouseId = UUID.randomUUID();

    when(warehouses.lifecycleIntegrationEnabled()).thenReturn(true);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(warehouses.lifecycleReadinessWork(null, 100))
        .thenReturn(
            new WarehouseLifecycleReadinessWorkPage(
                List.of(new WarehouseLifecycleReadinessWork(warehouseId, 7L, "DRAINING")), null));
    when(lifecycle.beginReadiness(warehouseId, 7L))
        .thenReturn(new AssetWarehouseLifecycleStore.ReadinessAttempt(warehouseId, 7L, false, false));

    reconciler.reconcile();

    verify(warehouses, never()).confirmLifecycleReadiness(any(), anyLong());
    verify(lifecycle, never()).sealReadiness(any(), anyLong());
    verify(lifecycle, never()).releaseReadiness(any(), anyLong());
  }

  @Test
  void releasesOnlyAConfirmationFenceWhoseRemoteRequestWasRejected() {
    WarehouseRegistryClient warehouses = mock(WarehouseRegistryClient.class);
    AssetWarehouseLifecycleStore lifecycle = mock(AssetWarehouseLifecycleStore.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AssetWarehouseLifecycleReconciler reconciler =
        new AssetWarehouseLifecycleReconciler(warehouses, lifecycle, jdbc);
    UUID warehouseId = UUID.randomUUID();

    when(warehouses.lifecycleIntegrationEnabled()).thenReturn(true);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(warehouses.lifecycleReadinessWork(null, 100))
        .thenReturn(
            new WarehouseLifecycleReadinessWorkPage(
                List.of(new WarehouseLifecycleReadinessWork(warehouseId, 7L, "DRAINING")), null));
    when(lifecycle.beginReadiness(warehouseId, 7L))
        .thenReturn(new AssetWarehouseLifecycleStore.ReadinessAttempt(warehouseId, 7L, false, true));
    doThrow(new AssetDependencyException(HttpStatus.CONFLICT, "lifecycle moved"))
        .when(warehouses)
        .confirmLifecycleReadiness(warehouseId, 7L);

    reconciler.reconcile();

    verify(lifecycle).releaseReadiness(warehouseId, 7L);
    verify(lifecycle, never()).sealReadiness(any(), anyLong());
  }

  @Test
  void preservesAnUncertainConfirmationFenceForSafeReplay() {
    WarehouseRegistryClient warehouses = mock(WarehouseRegistryClient.class);
    AssetWarehouseLifecycleStore lifecycle = mock(AssetWarehouseLifecycleStore.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AssetWarehouseLifecycleReconciler reconciler =
        new AssetWarehouseLifecycleReconciler(warehouses, lifecycle, jdbc);
    UUID warehouseId = UUID.randomUUID();

    when(warehouses.lifecycleIntegrationEnabled()).thenReturn(true);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(warehouses.lifecycleReadinessWork(null, 100))
        .thenReturn(
            new WarehouseLifecycleReadinessWorkPage(
                List.of(new WarehouseLifecycleReadinessWork(warehouseId, 7L, "DRAINING")), null));
    when(lifecycle.beginReadiness(warehouseId, 7L))
        .thenReturn(new AssetWarehouseLifecycleStore.ReadinessAttempt(warehouseId, 7L, false, true));
    doThrow(new AssetDependencyException(HttpStatus.SERVICE_UNAVAILABLE, "response uncertain"))
        .when(warehouses)
        .confirmLifecycleReadiness(warehouseId, 7L);

    reconciler.reconcile();

    verify(lifecycle, never()).releaseReadiness(any(), anyLong());
    verify(lifecycle, never()).sealReadiness(any(), anyLong());
  }
}
