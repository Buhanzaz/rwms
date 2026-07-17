package dev.buhanzaz.rwms.asset.repository;

import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureMember;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetCaptureMemberId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryAssetCaptureMemberRepository
    extends JpaRepository<InventoryAssetCaptureMember, InventoryAssetCaptureMemberId> {
  @Query("""
      select value from InventoryAssetCaptureMember value
      where value.id.captureId = :captureId and value.id.sequenceNo > :after
      order by value.id.sequenceNo
      """)
  List<InventoryAssetCaptureMember> pageAfter(
      @Param("captureId") UUID captureId,
      @Param("after") long after,
      Pageable pageable);
}
