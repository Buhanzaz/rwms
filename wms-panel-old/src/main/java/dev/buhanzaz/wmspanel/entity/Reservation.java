package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@JmixEntity
@Table(name = "RESERVATION", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RESERVATION_UNQ_NUMBER", columnNames = "RESERVATION_NUMBER")
})
@Entity
public class Reservation extends FullAuditEntity {

    @NotNull
    @Column(name = "RESERVATION_NUMBER", nullable = false, length = 64)
    private String reservationNumber;

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @JoinColumn(name = "RENTAL_ITEM_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private RentalItem rentalItem;

    @Column(name = "RESERVATION_TYPE", nullable = false, length = 32)
    private String reservationType;

    @Column(name = "RESERVATION_STATUS", nullable = false, length = 32)
    private String status = ReservationStatus.TEMPORARY.getId();

    @JoinColumn(name = "RESPONSIBLE_RENTAL_MANAGER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private User responsibleRentalManager;

    @NotNull
    @Column(name = "CLIENT_TYPE", nullable = false, length = 32)
    private String clientType = ReservationClientType.INDIVIDUAL.getId();

    @Column(name = "INDIVIDUAL_LAST_NAME", length = 128)
    private String individualLastName;

    @Column(name = "INDIVIDUAL_FIRST_NAME", length = 128)
    private String individualFirstName;

    @Column(name = "INDIVIDUAL_MIDDLE_NAME", length = 128)
    private String individualMiddleName;

    @Column(name = "COMPANY_NAME")
    private String companyName;

    @Column(name = "LEGAL_ENTITY")
    private String legalEntity;

    @Column(name = "CONTACT_PERSON")
    private String contactPerson;

    @Column(name = "RESERVED_BY", length = 128)
    private String reservedBy;

    @Column(name = "RESERVED_AT")
    private OffsetDateTime reservedAt;

    @Column(name = "EXPIRES_AT")
    private OffsetDateTime temporaryExpiresAt;

    @Column(name = "PAYMENT_DUE_AT")
    private OffsetDateTime paymentDueAt;

    @Column(name = "CONFIRMED_AT")
    private OffsetDateTime confirmedAt;

    @Column(name = "CANCELLED_AT")
    private OffsetDateTime cancelledAt;

    @Column(name = "CANCEL_REASON", length = 1000)
    private String cancelReason;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @OneToMany(mappedBy = "reservation")
    private List<ReservationLine> reservationItems = new ArrayList<>();

    public String getReservationNumber() {
        return reservationNumber;
    }

    public void setReservationNumber(String reservationNumber) {
        this.reservationNumber = reservationNumber;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public RentalItem getRentalItem() {
        return rentalItem;
    }

    public void setRentalItem(RentalItem rentalItem) {
        this.rentalItem = rentalItem;
    }

    public ReservationType getReservationType() {
        return ReservationType.fromId(reservationType);
    }

    public void setReservationType(ReservationType reservationType) {
        this.reservationType = reservationType == null ? null : reservationType.getId();
    }

    public ReservationStatus getReservationStatus() {
        return ReservationStatus.fromId(status);
    }

    public void setReservationStatus(ReservationStatus reservationStatus) {
        this.status = reservationStatus == null ? null : reservationStatus.getId();
    }

    public ReservationStatus getStatus() {
        return getReservationStatus();
    }

    public void setStatus(ReservationStatus status) {
        setReservationStatus(status);
    }

    public User getResponsibleRentalManager() {
        return responsibleRentalManager;
    }

    public void setResponsibleRentalManager(User responsibleRentalManager) {
        this.responsibleRentalManager = responsibleRentalManager;
    }

    public ReservationClientType getClientType() {
        return ReservationClientType.fromId(clientType);
    }

    public void setClientType(ReservationClientType clientType) {
        this.clientType = clientType == null ? null : clientType.getId();
    }

    public String getIndividualLastName() {
        return individualLastName;
    }

    public void setIndividualLastName(String individualLastName) {
        this.individualLastName = individualLastName;
    }

    public String getIndividualFirstName() {
        return individualFirstName;
    }

    public void setIndividualFirstName(String individualFirstName) {
        this.individualFirstName = individualFirstName;
    }

    public String getIndividualMiddleName() {
        return individualMiddleName;
    }

    public void setIndividualMiddleName(String individualMiddleName) {
        this.individualMiddleName = individualMiddleName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getLegalEntity() {
        return legalEntity;
    }

    public void setLegalEntity(String legalEntity) {
        this.legalEntity = legalEntity;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public void setContactPerson(String contactPerson) {
        this.contactPerson = contactPerson;
    }

    public String getReservedBy() {
        return reservedBy;
    }

    public void setReservedBy(String reservedBy) {
        this.reservedBy = reservedBy;
    }

    public OffsetDateTime getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(OffsetDateTime reservedAt) {
        this.reservedAt = reservedAt;
    }

    public OffsetDateTime getTemporaryExpiresAt() {
        return temporaryExpiresAt;
    }

    public void setTemporaryExpiresAt(OffsetDateTime temporaryExpiresAt) {
        this.temporaryExpiresAt = temporaryExpiresAt;
    }

    public OffsetDateTime getExpiresAt() {
        return getTemporaryExpiresAt();
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        setTemporaryExpiresAt(expiresAt);
    }

    public OffsetDateTime getPaymentDueAt() {
        return paymentDueAt;
    }

    public void setPaymentDueAt(OffsetDateTime paymentDueAt) {
        this.paymentDueAt = paymentDueAt;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(OffsetDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(OffsetDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<ReservationLine> getReservationItems() {
        return reservationItems;
    }

    public void setReservationItems(List<ReservationLine> reservationItems) {
        this.reservationItems = reservationItems;
    }

    @jakarta.persistence.Transient
    public OffsetDateTime getCreatedAt() {
        return getCreatedDate();
    }

    @jakarta.persistence.Transient
    public OffsetDateTime getUpdatedAt() {
        return getLastModifiedDate();
    }
}
