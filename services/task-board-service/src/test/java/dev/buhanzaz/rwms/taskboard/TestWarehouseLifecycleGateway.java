package dev.buhanzaz.rwms.taskboard;

import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-profile adapter only; production always uses the HTTP lifecycle boundary. */
@Component
@Primary
@Profile("test")
public class TestWarehouseLifecycleGateway implements WarehouseLifecycleGateway {
  private final List<Admission> admissions = new CopyOnWriteArrayList<>();
  private final List<Boolean> admissionTransactionStates = new CopyOnWriteArrayList<>();
  private final List<Confirmation> confirmations = new CopyOnWriteArrayList<>();
  private final List<Boolean> confirmationTransactionStates = new CopyOnWriteArrayList<>();
  private volatile LifecycleState lifecycleState = LifecycleState.ACTIVE;
  private volatile ReadinessWorkPage readinessWorkPage = new ReadinessWorkPage(List.of(), null);
  private volatile RuntimeException confirmationFailure;

  @Override
  public void requireAdmission(UUID warehouseId, OperationDirection direction) {
    admissionTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
    admissions.add(new Admission(warehouseId, direction));
    boolean admitted =
        switch (lifecycleState) {
          case ACTIVE -> true;
          case DRAINING -> direction == OperationDirection.OUTGOING;
          case INACTIVE -> false;
        };
    if (!admitted) {
      throw new ConflictException("Test warehouse lifecycle rejects this operation");
    }
  }

  @Override
  public ReadinessWorkPage readinessWork(UUID after, int limit) {
    return readinessWorkPage;
  }

  @Override
  public void confirmReadiness(UUID warehouseId, long expectedVersion) {
    confirmationTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
    RuntimeException failure = confirmationFailure;
    if (failure != null) {
      throw failure;
    }
    confirmations.add(new Confirmation(warehouseId, expectedVersion));
  }

  void lifecycleState(LifecycleState value) {
    lifecycleState = value;
  }

  void readinessWorkPage(ReadinessWorkPage value) {
    readinessWorkPage = value;
  }

  void confirmationFailure(RuntimeException value) {
    confirmationFailure = value;
  }

  List<Admission> admissions() {
    return List.copyOf(admissions);
  }

  List<Boolean> admissionTransactionStates() {
    return List.copyOf(admissionTransactionStates);
  }

  List<Confirmation> confirmations() {
    return List.copyOf(confirmations);
  }

  List<Boolean> confirmationTransactionStates() {
    return List.copyOf(confirmationTransactionStates);
  }

  void reset() {
    lifecycleState = LifecycleState.ACTIVE;
    readinessWorkPage = new ReadinessWorkPage(List.of(), null);
    confirmationFailure = null;
    admissions.clear();
    admissionTransactionStates.clear();
    confirmations.clear();
    confirmationTransactionStates.clear();
  }

  enum LifecycleState {
    ACTIVE,
    DRAINING,
    INACTIVE
  }

  record Admission(UUID warehouseId, OperationDirection direction) {}

  record Confirmation(UUID warehouseId, long expectedVersion) {}
}
