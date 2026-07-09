package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RentalItemStatus implements EnumClass<String> {
    READY("READY"),
    TEMP_RESERVED("TEMP_RESERVED"),
    RESERVED("RESERVED"),
    IN_RENT("IN_RENT"),
    NEED_INSPECTION("NEED_INSPECTION"),
    WAITING_ESTIMATE_CONFIRMATION("WAITING_ESTIMATE_CONFIRMATION"),
    WAITING_REPAIR("WAITING_REPAIR"),
    IN_REPAIR("IN_REPAIR"),
    WAITING_REPAIR_CHECK("WAITING_REPAIR_CHECK"),
    IN_CAP_REPAIR("IN_CAP_REPAIR");

    private final String id;

    RentalItemStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RentalItemStatus fromId(String id) {
        for (RentalItemStatus value : RentalItemStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
