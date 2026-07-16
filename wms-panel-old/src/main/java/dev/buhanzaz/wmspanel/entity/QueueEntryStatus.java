package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum QueueEntryStatus implements EnumClass<String> {
    WAITING("WAITING"),
    IN_PROGRESS("IN_PROGRESS"),
    PAUSED("PAUSED"),
    DONE("DONE"),
    CANCELLED("CANCELLED");

    private final String id;

    QueueEntryStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static QueueEntryStatus fromId(String id) {
        for (QueueEntryStatus value : QueueEntryStatus.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
