package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

public enum WorkQueueKind implements EnumClass<String> {
    MOVEMENT("MOVEMENT"),
    REPAIR("REPAIR"),
    HOLDING("HOLDING");

    private final String id;

    WorkQueueKind(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static WorkQueueKind fromId(String id) {
        if ("CAPITAL_REPAIR".equals(id)) {
            return HOLDING;
        }
        for (WorkQueueKind at : WorkQueueKind.values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}
