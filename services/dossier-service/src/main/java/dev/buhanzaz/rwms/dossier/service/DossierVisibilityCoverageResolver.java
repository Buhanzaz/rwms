package dev.buhanzaz.rwms.dossier.service;

import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierSourceFact;
import dev.buhanzaz.rwms.dossier.eventing.DossierValidatedEvent;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSubjectAssociationRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves the cabin visibility scope of a validated failure from generation-local subject proof
 * or the immutable source snapshot. It never treats aggregate or secondary identifiers as cabin
 * identities and never accepts a direct cabin without its validated warehouse pair.
 */
@Service
public class DossierVisibilityCoverageResolver {
  private final DossierSubjectAssociationRepository associations;
  private final DossierSourceFactRepository sourceFacts;

  /** Creates the resolver over the two service-local stores that can retain subject proof. */
  public DossierVisibilityCoverageResolver(
      DossierSubjectAssociationRepository associations,
      DossierSourceFactRepository sourceFacts) {
    this.associations = associations;
    this.sourceFacts = sourceFacts;
  }

  /**
   * Returns a proven cabin for the supplied generation or null when the subject remains global.
   * Association proof is generation-local; a journal snapshot is accepted only through its
   * constrained subject-cabin field; direct event proof requires a validated cabin/warehouse pair.
   * Conflicting proof remains unscoped instead of selecting either cabin.
   */
  public UUID provenSubjectCabin(
      DossierValidatedEvent event, DossierProducer producer, UUID generationId) {
    DossierProducer associationProducer = producer;
    String associationType = event.aggregateType();
    UUID associationSourceId = event.aggregateId();
    if (producer == DossierProducer.MEDIA && event.cabinId() != null) {
      associationProducer = DossierProducer.ASSET;
      associationType = "RENTAL_ITEM";
      associationSourceId = event.cabinId();
    } else if (producer == DossierProducer.MEDIA
        || (producer == DossierProducer.INVENTORY
            && "PUBLICATION".equals(event.aggregateType()))) {
      associationProducer = DossierProducer.INVENTORY;
      associationType = "FINDING";
      associationSourceId = event.secondaryId();
    } else if (producer == DossierProducer.INVENTORY) {
      associationType = "FINDING";
      associationSourceId = event.secondaryId();
    }
    Optional<UUID> associatedCabin =
        associationSourceId == null
            ? Optional.empty()
            : associations
                .findByProducerAndSourceTypeAndSourceIdAndGenerationId(
                    associationProducer, associationType, associationSourceId, generationId)
                .map(value -> value.getCabinId());
    Optional<UUID> journaledCabin =
        sourceFacts
            .findByEventId(event.eventId())
            .map(DossierSourceFact::getSubjectCabinId);
    UUID directCabin =
        event.cabinId() != null && event.warehouseId() != null ? event.cabinId() : null;
    Set<UUID> provenCabins = new HashSet<>();
    associatedCabin.ifPresent(provenCabins::add);
    journaledCabin.ifPresent(provenCabins::add);
    if (directCabin != null) provenCabins.add(directCabin);
    return provenCabins.size() == 1 ? provenCabins.iterator().next() : null;
  }
}
