package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.WorkQueue;
import dev.buhanzaz.rwms.taskboard.domain.WorkQueueClassBinding;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerClassAssignment;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Owns warehouse-scoped worker, qualification and group lifecycle transitions.
 *
 * <p>Credential commands are coordinated with {@code auth-service}, but worker state remains
 * authoritative here. Cross-service failures are represented as durable pending/error state and
 * reconciled explicitly instead of pretending that a remote credential change succeeded.
 *
 * <p>This facade preserves the task-board service seam while delegating each cohesive workflow to
 * its owner: worker profiles, credential lifecycle, or group availability and membership.
 */
@Service
public class WorkforceService {
  private final WorkforceProfileService profiles;
  private final WorkforceCredentialLifecycleService credentialLifecycle;
  private final WorkforceGroupService groups;

  @Autowired
  WorkforceService(
      WorkforceProfileService profiles,
      WorkforceCredentialLifecycleService credentialLifecycle,
      WorkforceGroupService groups) {
    this.profiles = profiles;
    this.credentialLifecycle = credentialLifecycle;
    this.groups = groups;
  }

  /** Lists workers in canonical display-name order for one warehouse. */
  public List<WorkerDto> listWorkers(UUID warehouseId) {
    return profiles.listWorkers(warehouseId);
  }

  /** Creates a worker profile, qualifications and requested credential provisioning operation. */
  public WorkerDto createWorker(UUID warehouseId, WorkerRequest request) {
    return profiles.createWorker(warehouseId, request);
  }

  /** Replaces a version-fenced worker profile and coordinates any credential change. */
  public WorkerDto updateWorker(UUID warehouseId, UUID id, WorkerRequest request) {
    return profiles.updateWorker(warehouseId, id, request);
  }

  /** Sets a worker's active group under the worker version fence. */
  public WorkerDto setCurrentGroup(
      UUID warehouseId, UUID workerId, SetCurrentGroupRequest request) {
    return groups.setCurrentGroup(warehouseId, workerId, request);
  }

  WorkerGroupDto disableGroupState(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    return groups.disableGroupState(warehouseId, groupId, request);
  }

  WorkerGroupDto enableGroupState(
      UUID warehouseId, UUID groupId, GroupAvailabilityRequest request) {
    return groups.enableGroupState(warehouseId, groupId, request);
  }

  /** Starts a version-fenced credential reset without exposing the secret in emitted facts. */
  public WorkerDto resetPassword(UUID warehouseId, UUID id, long expectedVersion, String password) {
    return credentialLifecycle.resetPassword(warehouseId, id, expectedVersion, password);
  }

  /** Starts durable credential disabling for a version-fenced worker. */
  public WorkerDto disableCredentials(UUID warehouseId, UUID id, long expectedVersion) {
    return credentialLifecycle.disableCredentials(warehouseId, id, expectedVersion);
  }

  /** Starts durable credential enabling for a version-fenced worker. */
  public WorkerDto enableCredentials(UUID warehouseId, UUID id, long expectedVersion) {
    return credentialLifecycle.enableCredentials(warehouseId, id, expectedVersion);
  }

  /** Reconciles a failed or pending credential-disable operation. */
  public WorkerDto reconcileDisableCredentials(
      UUID warehouseId, UUID id, long expectedVersion) {
    return credentialLifecycle.reconcileDisableCredentials(warehouseId, id, expectedVersion);
  }

  /** Deletes an unreferenced worker after the credential-side operation is settled. */
  public void deleteWorker(UUID warehouseId, UUID id, long expectedVersion) {
    credentialLifecycle.deleteWorker(warehouseId, id, expectedVersion);
  }

  /** Lists worker groups for one warehouse. */
  public List<WorkerGroupDto> listGroups(UUID warehouseId) {
    return groups.listGroups(warehouseId);
  }

  /** Creates a group and atomically applies any version-fenced current-worker changes. */
  public WorkerGroupDto createGroup(UUID warehouseId, WorkerGroupRequest request) {
    return groups.createGroup(warehouseId, request);
  }

  /** Replaces a group, memberships, and requested current-worker changes in one transaction. */
  public WorkerGroupDto updateGroup(UUID warehouseId, UUID id, WorkerGroupRequest request) {
    return groups.updateGroup(warehouseId, id, request);
  }

  /** Deletes an unused group under its expected version. */
  public void deleteGroup(UUID warehouseId, UUID id, long expectedVersion) {
    groups.deleteGroup(warehouseId, id, expectedVersion);
  }

  public List<WorkerGroup> eligibleGroups(
      WorkQueue queue, List<WorkQueueClassBinding> queueBindings) {
    return groups.eligibleGroups(queue, queueBindings);
  }

  public Worker requireWorker(UUID warehouseId, UUID id) {
    return profiles.requireWorker(warehouseId, id);
  }

  public WorkerGroup requireGroup(UUID warehouseId, UUID id) {
    return groups.requireGroup(warehouseId, id);
  }

  public List<WorkerClassAssignment> activeQualifications(UUID workerId) {
    return profiles.activeQualifications(workerId);
  }
}
