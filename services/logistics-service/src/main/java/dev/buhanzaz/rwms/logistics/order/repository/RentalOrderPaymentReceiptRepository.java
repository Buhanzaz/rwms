package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentReceipt;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Stores one immutable initial payment bill per logistics order. */
public interface RentalOrderPaymentReceiptRepository
    extends JpaRepository<RentalOrderPaymentReceipt, UUID> {
  Optional<RentalOrderPaymentReceipt> findByOrder_Id(UUID orderId);

  @Query(value = "select clock_timestamp()", nativeQuery = true)
  Instant currentDatabaseTimestamp();
}
