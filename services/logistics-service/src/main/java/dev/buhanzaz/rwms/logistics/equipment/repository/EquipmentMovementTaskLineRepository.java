package dev.buhanzaz.rwms.logistics.equipment.repository;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLineState;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentMovementTaskLineRepository
    extends JpaRepository<EquipmentMovementTaskLine, UUID> {
  List<EquipmentMovementTaskLine> findAllByTask_IdOrderByLineNumberAsc(UUID taskId);

  Optional<EquipmentMovementTaskLine> findFirstByTask_IdAndStateOrderByLineNumberAsc(
      UUID taskId, EquipmentMovementLineState state);
}
