package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WorkerGroupMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerGroupMemberRepository extends JpaRepository<WorkerGroupMember, UUID> {
  List<WorkerGroupMember> findAllByWorkerGroupId(UUID groupId);

  List<WorkerGroupMember> findAllByWorkerGroupIdAndActiveTrue(UUID groupId);

  boolean existsByWorkerGroupId(UUID groupId);

  boolean existsByWorkerId(UUID workerId);
}
