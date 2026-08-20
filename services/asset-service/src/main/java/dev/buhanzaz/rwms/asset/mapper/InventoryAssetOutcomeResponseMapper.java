package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeResponse;
import dev.buhanzaz.rwms.asset.domain.InventoryAssetOutcomeReceipt;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps the durable inventory-outcome receipt to its frozen private-boundary response. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface InventoryAssetOutcomeResponseMapper {
  @Mapping(target = "assetVersion", source = "responseAssetVersion")
  @Mapping(target = "status", source = "responseStatus")
  InventoryOutcomeResponse toResponse(InventoryAssetOutcomeReceipt receipt);
}
