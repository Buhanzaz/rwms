package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum ReservationItemStatus implements EnumClass<String> {
    ACTIVE("ACTIVE"),
    RESERVED("RESERVED"),
    RELEASED("RELEASED"),
    CANCELLED("CANCELLED"),
    EXPIRED("EXPIRED"),
    COMPLETED("COMPLETED");

    private final String id;

    ReservationItemStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static ReservationItemStatus fromId(String id) {
        for (ReservationItemStatus value : ReservationItemStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
