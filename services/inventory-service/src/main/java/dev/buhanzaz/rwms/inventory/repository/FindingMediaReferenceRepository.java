package dev.buhanzaz.rwms.inventory.repository;

import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for service-local finding media reference persistence.
 */
public interface FindingMediaReferenceRepository
    extends JpaRepository<FindingMediaReference, FindingMediaReference.Key> {
  List<FindingMediaReference> findAllByFindingIdAndFindingRevisionOrderByMediaIdAscGenerationAsc(
      UUID findingId, long findingRevision);

  long countByFindingIdAndFindingRevision(UUID findingId, long findingRevision);

  @org.springframework.data.jpa.repository.Query(
      """
      select reference
        from FindingMediaReference reference, InventoryFinding finding,
             InventoryMediaFactProjection media
       where finding.id in :findingIds
         and reference.findingId=finding.id
         and reference.findingRevision=finding.revision
         and media.mediaId=reference.mediaId
         and media.generation=reference.generation
         and media.ownerType='INVENTORY_FINDING'
         and media.ownerId=finding.id
         and media.mediaStatus='READY'
       order by reference.findingId,reference.mediaId,reference.generation
      """)
  List<FindingMediaReference> findActiveByFindingIds(
      @org.springframework.data.repository.query.Param("findingIds") Set<UUID> findingIds);
}
