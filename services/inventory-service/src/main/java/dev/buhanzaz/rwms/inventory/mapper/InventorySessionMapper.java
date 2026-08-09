package dev.buhanzaz.rwms.inventory.mapper;

import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.InventoryActorView;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.MembershipMovementView;
import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovement;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct mapper for inventory session boundary representations.
 * It maps data without performing a domain transition.
 */
@Mapper(
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    componentModel = MappingConstants.ComponentModel.SPRING)
public interface InventorySessionMapper {
  @Mapping(source = "startedBySubjectId", target = "id")
  @Mapping(source = "startedByDisplayName", target = "displayName")
  InventoryActorView toInventoryActorView(InventorySession inventorySession);

  MembershipMovementView toMembershipMovementView(InventoryMembershipMovement movement);
}
