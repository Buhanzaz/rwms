package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps a maintenance entity read to the safe private logistics-source snapshot. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LogisticsReturnShortageResponseMapper {
  @Mapping(target = "returnId", source = "id.returnId")
  @Mapping(target = "lineId", source = "id.lineId")
  @Mapping(target = "sourceVersion", source = "version")
  @Mapping(target = "rentalItemVersion", source = "rentalItemVersionSnapshot")
  @Mapping(target = "receivedAt", source = "createdAt")
  StoredSnapshot toStoredSnapshot(LogisticsReturnShortage source);

  record StoredSnapshot(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      String sourceSha256,
      String snapshotSha256,
      String shortageSnapshot,
      OffsetDateTime receivedAt) {}
}
