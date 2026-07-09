package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum ReservationAccessoryStatus implements EnumClass<String> {
    ACTIVE("ACTIVE"),
    RELEASED("RELEASED"),
    CANCELLED("CANCELLED"),
    EXPIRED("EXPIRED"),
    COMPLETED("COMPLETED");

    private final String id;

    ReservationAccessoryStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static ReservationAccessoryStatus fromId(String id) {
        for (ReservationAccessoryStatus value : ReservationAccessoryStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
