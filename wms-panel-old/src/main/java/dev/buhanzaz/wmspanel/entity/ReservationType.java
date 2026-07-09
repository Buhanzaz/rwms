package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum ReservationType implements EnumClass<String> {
    TEMPORARY("TEMPORARY"),
    CLIENT("CLIENT");

    private final String id;

    ReservationType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static ReservationType fromId(String id) {
        for (ReservationType value : ReservationType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
