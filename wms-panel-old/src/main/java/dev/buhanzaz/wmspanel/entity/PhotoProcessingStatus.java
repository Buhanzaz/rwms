package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

public enum PhotoProcessingStatus implements EnumClass<String> {
    PENDING("PENDING"),
    READY("READY"),
    FAILED("FAILED");

    private final String id;

    PhotoProcessingStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static PhotoProcessingStatus fromId(String id) {
        for (PhotoProcessingStatus value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
