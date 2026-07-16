package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Table(name = "RESERVATION_SEARCH_SETTINGS", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_RES_SEARCH_SETTINGS_UNQ_CODE", columnNames = "CODE")
})
@Entity
public class ReservationSearchSettings extends FullAuditEntity {

    @NotNull
    @Column(name = "CODE", nullable = false, length = 32)
    private String code;

    @Column(name = "RESERVATION_SEARCH_REFRESH_SECONDS")
    private Integer reservationSearchRefreshSeconds = 10;

    @InstanceName
    public String getDisplayName() {
        return code == null || code.isBlank() ? "DEFAULT" : code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getReservationSearchRefreshSeconds() {
        return reservationSearchRefreshSeconds;
    }

    public void setReservationSearchRefreshSeconds(Integer reservationSearchRefreshSeconds) {
        this.reservationSearchRefreshSeconds = reservationSearchRefreshSeconds;
    }
}
