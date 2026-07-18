package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentHoldResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentHoldResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsEquipmentContentSnapshot;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsOperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemSnapshot;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.OperationLeaseResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps only safe asset read projections for the logistics private boundary. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AssetLogisticsResponseMapper {
  @Mapping(target = "assetId", source = "id")
  LogisticsRentalItemSnapshot toLogisticsSnapshot(RentalItemResponse response);

  LogisticsEquipmentContentSnapshot toLogisticsContent(
      dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentContentResponse response);

  @Mapping(target = "leaseId", source = "id")
  LogisticsOperationLeaseResponse toLogisticsLease(OperationLeaseResponse response);

  @Mapping(target = "holdId", source = "id")
  LogisticsEquipmentHoldResponse toLogisticsHold(EquipmentHoldResponse response);
}
