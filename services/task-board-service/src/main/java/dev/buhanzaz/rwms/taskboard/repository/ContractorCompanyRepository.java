package dev.buhanzaz.rwms.taskboard.repository;

import dev.buhanzaz.rwms.taskboard.domain.ContractorCompany;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** City-scoped company lookup and locks shared by membership and catalog commands. */
public interface ContractorCompanyRepository extends JpaRepository<ContractorCompany, UUID> {
  List<ContractorCompany> findAllByWarehouseIdOrderByNameAsc(UUID warehouseId);

  Optional<ContractorCompany> findByWarehouseIdAndInn(UUID warehouseId, String inn);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select company from ContractorCompany company where company.id = :id")
  Optional<ContractorCompany> findByIdForUpdate(@Param("id") UUID id);
}
