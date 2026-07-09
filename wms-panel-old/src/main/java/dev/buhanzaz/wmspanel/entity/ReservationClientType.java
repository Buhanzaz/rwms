package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum ReservationClientType implements EnumClass<String> {
    INDIVIDUAL("INDIVIDUAL"),
    LEGAL_ENTITY("LEGAL_ENTITY");

    private final String id;

    ReservationClientType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static ReservationClientType fromId(String id) {
        for (ReservationClientType value : ReservationClientType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
