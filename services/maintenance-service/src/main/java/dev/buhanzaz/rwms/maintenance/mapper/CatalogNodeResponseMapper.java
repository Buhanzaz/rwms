package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogNodeResponse;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CatalogNodeType;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.FurnitureEquipmentReference;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.MediaReferenceInput;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.OpaqueCatalogReference;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.RoutingSnapshot;
import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import java.util.List;
import java.util.UUID;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CatalogNodeResponseMapper {
  @Mapping(target = "id", source = "node.id")
  @Mapping(target = "catalogVersionId", source = "node.catalogVersionId")
  @Mapping(target = "mediaOwnerId", source = "mediaOwnerId")
  @Mapping(target = "code", source = "node.code")
  @Mapping(target = "nodeType", source = "nodeType")
  @Mapping(target = "name", source = "node.name")
  @Mapping(target = "active", source = "node.active")
  @Mapping(target = "parentNodeId", source = "node.parentNodeId")
  @Mapping(target = "furnitureCategory", source = "node.furnitureCategory")
  @Mapping(target = "furnitureEquipment", source = "furnitureEquipment")
  @Mapping(target = "unit", source = "node.unit")
  @Mapping(target = "unitPrice", source = "unitPrice")
  @Mapping(target = "durationMinutes", source = "node.durationMinutes")
  @Mapping(target = "includeInEstimate", source = "node.includeInEstimate")
  @Mapping(target = "commonItem", source = "node.commonItem")
  @Mapping(target = "showInMainMenu", source = "node.showInMainMenu")
  @Mapping(target = "photoRequired", source = "node.photoRequired")
  @Mapping(target = "routing", source = "routing")
  @Mapping(target = "references", source = "references")
  @Mapping(target = "comment", source = "node.comment")
  @Mapping(target = "mediaReferences", source = "mediaReferences")
  CatalogNodeResponse toResponse(
      CatalogNode node,
      UUID mediaOwnerId,
      CatalogNodeType nodeType,
      FurnitureEquipmentReference furnitureEquipment,
      String unitPrice,
      RoutingSnapshot routing,
      List<OpaqueCatalogReference> references,
      List<MediaReferenceInput> mediaReferences);
}
