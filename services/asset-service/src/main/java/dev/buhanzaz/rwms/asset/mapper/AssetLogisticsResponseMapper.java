package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentHoldResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsCabinPhotoPresentationSnapshot;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentHoldResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentContentSnapshot;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsOperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemSnapshot;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.OperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.service.CabinCompositionService.CabinComposition;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/** Maps only safe asset read projections for the logistics private boundary. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AssetLogisticsResponseMapper {
  @Mapping(target = "assetId", source = "id")
  LogisticsRentalItemSnapshot toLogisticsSnapshot(RentalItemResponse response);

  /** Maps only the immutable display facts approved for a public cabin photo presentation. */
  @Mapping(target = "assetId", source = "item.id")
  @Mapping(target = "version", source = "item.version")
  @Mapping(target = "warehouseId", source = "item.warehouseId")
  @Mapping(target = "number", source = "item.number")
  @Mapping(target = "dimensions", source = "composition.dimensions.name")
  @Mapping(target = "finishing", source = "composition.finishing.name")
  @Mapping(target = "category", source = "item.category")
  @Mapping(
      target = "characteristics",
      source = "composition.characteristics",
      qualifiedByName = "photoPresentationCharacteristicNames")
  @Mapping(target = "linoleum", source = "item.linoleum")
  LogisticsCabinPhotoPresentationSnapshot toPhotoPresentationSnapshot(
      RentalItem item, CabinComposition composition);

  /** Preserves exact catalog order and fails closed if persisted catalog names are malformed. */
  @Named("photoPresentationCharacteristicNames")
  default List<String> toPhotoPresentationCharacteristicNames(
      List<CabinCatalogValueResponse> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.stream()
        .map(
            value -> {
              if (value == null || value.name() == null || value.name().isBlank()) {
                throw new IllegalStateException("Cabin characteristic name is invalid");
              }
              return value.name().trim();
            })
        .toList();
  }

  LogisticsEquipmentContentSnapshot toLogisticsContent(
      dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentContentResponse response);

  @Mapping(target = "leaseId", source = "id")
  LogisticsOperationLeaseResponse toLogisticsLease(OperationLeaseResponse response);

  @Mapping(target = "holdId", source = "id")
  LogisticsEquipmentHoldResponse toLogisticsHold(EquipmentHoldResponse response);
}
