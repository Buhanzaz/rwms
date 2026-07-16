package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.datatype.EnumClass;

public enum BoardTaskPhotoType implements EnumClass<String> {
    BEFORE("BEFORE"),
    WORK("WORK"),
    AFTER("AFTER"),
    ACCEPTANCE("ACCEPTANCE");

    private final String id;

    BoardTaskPhotoType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    public static BoardTaskPhotoType fromId(String id) {
        for (BoardTaskPhotoType value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }
}
