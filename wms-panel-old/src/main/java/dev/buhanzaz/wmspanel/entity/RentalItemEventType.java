package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum RentalItemEventType implements EnumClass<String> {
    ESTIMATE("ESTIMATE"),
    INVENTORY_NEW_ITEM("INVENTORY_NEW_ITEM"),
    INVENTORY_EXISTING_ITEM("INVENTORY_EXISTING_ITEM"),
    ACCESSORY_UPDATED("ACCESSORY_UPDATED"),
    ESTIMATE_DRAFT("ESTIMATE_DRAFT"),
    ESTIMATE_COMPLETED("ESTIMATE_COMPLETED"),
    STATUS_CHANGED("STATUS_CHANGED"),
    REPAIR_TASK_STARTED("REPAIR_TASK_STARTED"),
    REPAIR_TASK_COMPLETED("REPAIR_TASK_COMPLETED"),
    REPAIR_READY_FOR_CHECK("REPAIR_READY_FOR_CHECK"),
    REPAIR_ACCEPTED("REPAIR_ACCEPTED"),
    BEFORE_RENT("BEFORE_RENT"),
    AFTER_RENT("AFTER_RENT"),
    AFTER_REPAIR("AFTER_REPAIR"),
    CAPITAL_REPAIR("CAPITAL_REPAIR"),
    MANUAL("MANUAL");

    private final String id;

    RentalItemEventType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static RentalItemEventType fromId(String id) {
        for (RentalItemEventType value : RentalItemEventType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
