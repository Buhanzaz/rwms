package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.AccessoryStockBalance;
import dev.buhanzaz.wmspanel.entity.Reservation;
import dev.buhanzaz.wmspanel.entity.ReservationAccessory;
import dev.buhanzaz.wmspanel.entity.ReservationAccessoryStatus;
import dev.buhanzaz.wmspanel.entity.ReservationItemStatus;
import dev.buhanzaz.wmspanel.entity.ReservationLine;
import dev.buhanzaz.wmspanel.entity.ReservationStatus;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReservationExpirationService {

    private final DataManager dataManager;
    private final ReservationService reservationService;

    public ReservationExpirationService(DataManager dataManager, ReservationService reservationService) {
        this.dataManager = dataManager;
        this.reservationService = reservationService;
    }

    @Transactional
    public void moveToWaitingPayment(UUID reservationId, OffsetDateTime paymentDueAt) {
        Reservation reservation = loadReservation(reservationId);
        ensureCanManage(reservation);
        if (reservation.getStatus() != ReservationStatus.TEMPORARY && reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new IllegalArgumentException("Перевести в ожидание оплаты можно только временный резерв");
        }

        reservation.setStatus(ReservationStatus.WAITING_PAYMENT);
        reservation.setReservationType(dev.buhanzaz.wmspanel.entity.ReservationType.CLIENT);
        reservation.setPaymentDueAt(paymentDueAt == null ? OffsetDateTime.now().plusDays(3) : paymentDueAt);
        promoteTemporaryItemsToReserved(reservation);
        dataManager.save(reservation);
    }

    @Transactional
    public void confirmPayment(UUID reservationId) {
        Reservation reservation = loadReservation(reservationId);
        ensureCanManage(reservation);
        if (reservation.getStatus() != ReservationStatus.WAITING_PAYMENT
                && reservation.getStatus() != ReservationStatus.TEMPORARY
                && reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new IllegalArgumentException("Подтвердить оплату можно только для активного резерва");
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setReservationType(dev.buhanzaz.wmspanel.entity.ReservationType.CLIENT);
        reservation.setConfirmedAt(OffsetDateTime.now());
        promoteTemporaryItemsToReserved(reservation);
        dataManager.save(reservation);
    }

    @Transactional
    public void cancelReservation(UUID reservationId, String cancelReason) {
        Reservation reservation = loadReservation(reservationId);
        ensureCanManage(reservation);
        ensureReservationIsOpen(reservation);
        releaseAllActiveItems(reservation, ReservationItemStatus.CANCELLED, ReservationAccessoryStatus.CANCELLED);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(OffsetDateTime.now());
        reservation.setCancelReason(normalize(cancelReason));
        dataManager.save(reservation);
    }

    @Transactional
    public void releaseReservationItem(UUID reservationItemId) {
        ReservationLine reservationItem = loadReservationItem(reservationItemId);
        Reservation reservation = reservationItem.getReservation();
        ensureCanManage(reservation);
        if (reservationItem.getStatus() != ReservationItemStatus.ACTIVE
                && reservationItem.getStatus() != ReservationItemStatus.RESERVED) {
            throw new IllegalArgumentException("Строка резерва уже освобождена");
        }
        releaseReservationItem(reservationItem, ReservationItemStatus.RELEASED, ReservationAccessoryStatus.RELEASED);
        SaveContext saveContext = new SaveContext();
        saveContext.saving(reservationItem);
        if (reservationItem.getRentalItem() != null) {
            saveContext.saving(reservationItem.getRentalItem());
        }
        dataManager.save(saveContext);
    }

    @Transactional
    public void deleteExpiredReservation(UUID reservationId) {
        Reservation reservation = loadReservation(reservationId);
        ensureCanManage(reservation);
        if (!isExpired(reservation, OffsetDateTime.now())) {
            throw new IllegalArgumentException("Удалить можно только истёкший резерв");
        }

        releaseAllActiveItems(reservation, ReservationItemStatus.EXPIRED, ReservationAccessoryStatus.EXPIRED);

        List<ReservationAccessory> accessories = dataManager.load(ReservationAccessory.class)
                .query("select e from ReservationAccessory e where e.reservation = :reservation")
                .parameter("reservation", reservation)
                .list();
        List<ReservationLine> lines = dataManager.load(ReservationLine.class)
                .query("select e from ReservationLine e where e.reservation = :reservation")
                .parameter("reservation", reservation)
                .list();

        if (!accessories.isEmpty()) {
            dataManager.remove(accessories.toArray());
        }
        if (!lines.isEmpty()) {
            dataManager.remove(lines.toArray());
        }
        dataManager.remove(reservation);
    }

    @Transactional
    public void expireTemporaryReservations() {
        expireReservations("""
                select e
                from Reservation e
                where e.status in ('ACTIVE', 'TEMPORARY')
                  and e.reservationType = 'TEMPORARY'
                  and e.temporaryExpiresAt is not null
                  and e.temporaryExpiresAt <= :now
                """);
    }

    @Transactional
    public void expireWaitingPaymentReservations() {
        expireReservations("""
                select e
                from Reservation e
                where e.status = 'WAITING_PAYMENT'
                  and e.paymentDueAt is not null
                  and e.paymentDueAt <= :now
                """);
    }

    private void expireReservations(String query) {
        List<Reservation> reservations = dataManager.load(Reservation.class)
                .query(query)
                .parameter("now", OffsetDateTime.now())
                .list();
        for (Reservation reservation : reservations) {
            ensureCanManage(reservation);
            releaseAllActiveItems(reservation, ReservationItemStatus.EXPIRED, ReservationAccessoryStatus.EXPIRED);
            reservation.setStatus(ReservationStatus.EXPIRED);
            dataManager.save(reservation);
        }
    }

    private void promoteTemporaryItemsToReserved(Reservation reservation) {
        List<ReservationLine> activeItems = activeReservationItems(reservation);
        SaveContext saveContext = new SaveContext();
        boolean changed = false;
        for (ReservationLine reservationItem : activeItems) {
            if (reservationItem.getStatus() == ReservationItemStatus.ACTIVE) {
                reservationItem.setStatus(ReservationItemStatus.RESERVED);
                changed = true;
            }
            if (reservationItem.getRentalItem() != null) {
                reservationItem.getRentalItem().setStatus("RESERVED");
                saveContext.saving(reservationItem.getRentalItem());
                changed = true;
            }
            saveContext.saving(reservationItem);
        }
        if (changed) {
            dataManager.save(saveContext);
        }
    }

    private void releaseAllActiveItems(Reservation reservation,
                                       ReservationItemStatus itemStatus,
                                       ReservationAccessoryStatus accessoryStatus) {
        List<ReservationLine> activeItems = activeReservationItems(reservation);
        SaveContext saveContext = new SaveContext();
        for (ReservationLine reservationItem : activeItems) {
            releaseReservationItem(reservationItem, itemStatus, accessoryStatus);
            saveContext.saving(reservationItem);
            if (reservationItem.getRentalItem() != null) {
                saveContext.saving(reservationItem.getRentalItem());
            }
        }
        dataManager.save(saveContext);
    }

    private void releaseReservationItem(ReservationLine reservationItem,
                                        ReservationItemStatus itemStatus,
                                        ReservationAccessoryStatus accessoryStatus) {
        releaseAccessories(reservationItem, accessoryStatus);
        reservationItem.setStatus(itemStatus);
        if (reservationItem.getRentalItem() != null) {
            reservationItem.getRentalItem().setStatus("READY");
        }
    }

    private void releaseAccessories(ReservationLine reservationItem, ReservationAccessoryStatus targetStatus) {
        List<ReservationAccessory> accessories = activeAccessories(reservationItem);
        SaveContext saveContext = new SaveContext();
        for (ReservationAccessory accessory : accessories) {
            AccessoryStockBalance balance = loadAccessoryBalance(accessory);
            balance.setQuantityAvailable(balance.getQuantityAvailable() + accessory.getQuantity());
            balance.setQuantityReserved(Math.max(0, balance.getQuantityReserved() - accessory.getQuantity()));
            accessory.setStatus(targetStatus);
            saveContext.saving(accessory, balance);
        }
        if (!accessories.isEmpty()) {
            dataManager.save(saveContext);
        }
    }

    private List<ReservationLine> activeReservationItems(Reservation reservation) {
        return dataManager.load(ReservationLine.class)
                .query("""
                        select e
                        from ReservationLine e
                        where e.reservation = :reservation
                          and (e.status is null or e.status = 'ACTIVE' or e.status = 'RESERVED')
                        order by e.createdDate
                        """)
                .parameter("reservation", reservation)
                .list();
    }

    private List<ReservationAccessory> activeAccessories(ReservationLine reservationItem) {
        return dataManager.load(ReservationAccessory.class)
                .query("""
                        select e
                        from ReservationAccessory e
                        where e.reservationItem = :reservationItem
                          and (e.status is null or e.status = 'ACTIVE')
                        order by e.createdDate
                        """)
                .parameter("reservationItem", reservationItem)
                .list();
    }

    private AccessoryStockBalance loadAccessoryBalance(ReservationAccessory accessory) {
        return dataManager.load(AccessoryStockBalance.class)
                .query("""
                        select e
                        from AccessoryStockBalance e
                        where e.warehouse = :warehouse
                          and e.accessoryItem = :accessoryItem
                        """)
                .parameter("warehouse", accessory.getReservation().getWarehouse())
                .parameter("accessoryItem", accessory.getAccessoryItem())
                .one();
    }

    private Reservation loadReservation(UUID reservationId) {
        return dataManager.load(Reservation.class)
                .id(reservationId)
                .one();
    }

    private ReservationLine loadReservationItem(UUID reservationItemId) {
        return dataManager.load(ReservationLine.class)
                .id(reservationItemId)
                .one();
    }

    private void ensureCanManage(Reservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("Резерв не найден");
        }
        if (!reservationService.canManageReservation(reservation)) {
            throw new IllegalArgumentException("Нет доступа к выбранному складу");
        }
    }

    private void ensureReservationIsOpen(Reservation reservation) {
        ReservationStatus status = reservation.getStatus();
        if (status == ReservationStatus.CANCELLED || status == ReservationStatus.EXPIRED || status == ReservationStatus.COMPLETED) {
            throw new IllegalArgumentException("Резерв уже закрыт");
        }
    }

    public boolean isExpired(Reservation reservation, OffsetDateTime now) {
        if (reservation == null) {
            return false;
        }
        ReservationStatus status = reservation.getStatus();
        if (status == ReservationStatus.EXPIRED) {
            return true;
        }
        if (status == ReservationStatus.TEMPORARY || status == ReservationStatus.ACTIVE) {
            return reservation.getTemporaryExpiresAt() != null
                    && !reservation.getTemporaryExpiresAt().isAfter(now == null ? OffsetDateTime.now() : now);
        }
        if (status == ReservationStatus.WAITING_PAYMENT) {
            return reservation.getPaymentDueAt() != null
                    && !reservation.getPaymentDueAt().isAfter(now == null ? OffsetDateTime.now() : now);
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
