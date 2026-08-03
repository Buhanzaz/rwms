package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.WarehouseMetadata;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseMetadataRepository extends JpaRepository<WarehouseMetadata, UUID> {
  List<WarehouseMetadata> findAllByActiveTrueOrderByIdAsc();
}
