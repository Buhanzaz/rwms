package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.api.RentalItemReserveSnapshot;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.mapper.RentalItemReserveSnapshotMapper;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads one consistent asset snapshot without expiring, releasing or renewing any reservation. */
@Service
@RequiredArgsConstructor
public class RentalItemReserveReadService {
  private final RentalItemRepository rentalItems;
  private final PresentationUnitHoldRepository holds;
  private final OrderUnitReservationRepository reservations;
  private final RentalItemReserveSnapshotMapper mapper;
  private final JdbcTemplate jdbc;

  /** Database time and repeatable reads keep conversion from appearing as two separate reserves. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RentalItemReserveSnapshot read(UUID rentalItemId, UUID warehouseId) {
    var item =
        rentalItems
            .findById(rentalItemId)
            .filter(value -> warehouseId.equals(value.getWarehouseId()))
            .orElseThrow(
                () -> new AssetNotFoundException("Rental item was not found in warehouse"));
    OffsetDateTime serverTime =
        jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    var liveHolds =
        holds
            .findAllLiveByRentalItemIdIn(
                List.of(item.getId()), PresentationUnitHoldState.ACTIVE, serverTime)
            .stream()
            .sorted(
                Comparator.comparing(PresentationUnitHold::getCreatedAt)
                    .thenComparing(PresentationUnitHold::getId))
            .map(mapper::toSelectionHold)
            .toList();
    var orderReservation =
        reservations
            .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
            .map(mapper::toOrderReservation)
            .orElse(null);
    return new RentalItemReserveSnapshot(
        item.getId(), item.getWarehouseId(), serverTime, liveHolds, orderReservation);
  }
}
