package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

@JmixEntity
@Table(name = "WORKER")
@Entity
public class Worker extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "WAREHOUSE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Warehouse warehouse;

    @Column(name = "FIRST_NAME", length = 128)
    private String firstName;

    @Column(name = "LAST_NAME", length = 128)
    private String lastName;

    @Column(name = "MIDDLE_NAME", length = 128)
    private String middleName;

    @InstanceName
    @NotNull
    @Column(name = "DISPLAY_NAME", nullable = false, length = 256)
    private String displayName;

    @Column(name = "ACTIVE")
    private Boolean active = true;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @Column(name = "APP_LOGIN", length = 128)
    private String appLogin;

    @Column(name = "APP_PASSWORD", length = 255)
    private String appPassword;

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public void setWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getAppLogin() {
        return appLogin;
    }

    public void setAppLogin(String appLogin) {
        this.appLogin = appLogin;
    }

    public String getAppPassword() {
        return appPassword;
    }

    public void setAppPassword(String appPassword) {
        this.appPassword = appPassword;
    }

    @PrePersist
    @PreUpdate
    private void normalizeBeforeSave() {
        String legacyDisplayName = trimToNull(this.displayName);
        this.firstName = trimToNull(this.firstName);
        this.lastName = trimToNull(this.lastName);
        this.middleName = trimToNull(this.middleName);
        this.appLogin = trimToNull(this.appLogin);
        this.appPassword = trimToNull(this.appPassword);
        String fullName = buildDisplayName();
        this.displayName = fullName != null ? fullName : legacyDisplayName;
    }

    private String buildDisplayName() {
        List<String> parts = new ArrayList<>();
        if (lastName != null) {
            parts.add(lastName);
        }
        if (firstName != null) {
            parts.add(firstName);
        }
        if (middleName != null) {
            parts.add(middleName);
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
