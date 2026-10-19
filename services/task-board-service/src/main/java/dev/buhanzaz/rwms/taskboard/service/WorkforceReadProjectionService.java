package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.GroupOperationalStatus;
import dev.buhanzaz.rwms.taskboard.domain.Worker;
import dev.buhanzaz.rwms.taskboard.domain.WorkerGroup;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupMemberRepository;
import org.springframework.stereotype.Service;

/**
 * Builds current worker and worker-group API projections from task-board-owned state.
 *
 * <p>This is deliberately read-only: lifecycle collaborators pass an already loaded aggregate
 * while their transaction remains active, so the projection cannot own mutation, authorization,
 * credentials, or transaction fencing.
 */
@Service
public class WorkforceReadProjectionService {
  private final WorkerClassAssignmentRepository qualifications;
  private final WorkerGroupMemberRepository members;
  private final RegistryService registry;

  public WorkforceReadProjectionService(
      WorkerClassAssignmentRepository qualifications,
      WorkerGroupMemberRepository members,
      RegistryService registry) {
    this.qualifications = qualifications;
    this.members = members;
    this.registry = registry;
  }

  WorkerDto workerDto(Worker worker) {
    return new WorkerDto(
        worker.getId(),
        worker.getVersion(),
        worker.getWarehouseId(),
        worker.getDisplayName(),
        worker.getFirstName(),
        worker.getLastName(),
        worker.getMiddleName(),
        worker.isActive(),
        worker.getComment(),
        worker.getAppLogin(),
        worker.getCredentialStatus(),
        worker.getCredentialError(),
        worker.getCurrentGroup() == null ? null : worker.getCurrentGroup().getId(),
        worker.getCurrentGroup() == null ? null : worker.getCurrentGroup().getName(),
        worker.getCurrentGroup() == null
            ? GroupOperationalStatus.DISABLED
            : worker.getCurrentGroup().getOperationalStatus(),
        qualifications.findAllByWorkerId(worker.getId()).stream()
            .map(
                qualification ->
                    new QualificationDto(
                        qualification.getId(),
                        qualification.getVersion(),
                        registry.dto(qualification.getWorkerClass()),
                        qualification.isActive(),
                        qualification.getComment()))
            .toList());
  }

  WorkerGroupDto groupDto(WorkerGroup group) {
    return new WorkerGroupDto(
        group.getId(),
        group.getVersion(),
        group.getWarehouseId(),
        registry.dto(group.getWorkerClass()),
        group.getName(),
        group.getDescription(),
        group.isActive(),
        group.getOperationalStatus(),
        group.getUnavailableSince(),
        group.getUnavailabilityReason(),
        members.findAllByWorkerGroupId(group.getId()).stream()
            .map(
                member ->
                    new GroupMemberDto(
                        member.getId(),
                        member.getVersion(),
                        member.getWorker().getId(),
                        member.getWorker().getDisplayName(),
                        member.isActive()))
            .toList());
  }
}
