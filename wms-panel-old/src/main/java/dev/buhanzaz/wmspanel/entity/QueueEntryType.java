package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum QueueEntryType implements EnumClass<String> {
    REAL("REAL"),
    SHADOW("SHADOW");

    private final String id;

    QueueEntryType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static QueueEntryType fromId(String id) {
        for (QueueEntryType value : QueueEntryType.values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
