package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum ReservationStatus implements EnumClass<String> {
    ACTIVE("ACTIVE"),
    TEMPORARY("TEMPORARY"),
    WAITING_PAYMENT("WAITING_PAYMENT"),
    CONFIRMED("CONFIRMED"),
    EXPIRED("EXPIRED"),
    CANCELLED("CANCELLED"),
    COMPLETED("COMPLETED");

    private final String id;

    ReservationStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static ReservationStatus fromId(String id) {
        for (ReservationStatus value : ReservationStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
