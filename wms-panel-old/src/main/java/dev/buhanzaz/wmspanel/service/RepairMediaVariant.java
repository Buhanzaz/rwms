package dev.buhanzaz.wmspanel.service;

public enum RepairMediaVariant {
    ORIGINAL("original"),
    PREVIEW("preview"),
    THUMB("thumb"),
    TINY("tiny");

    private final String id;

    RepairMediaVariant(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static RepairMediaVariant from(String value) {
        if (value == null || value.isBlank()) {
            return PREVIEW;
        }
        for (RepairMediaVariant variant : values()) {
            if (variant.id.equalsIgnoreCase(value)) {
                return variant;
            }
        }
        return PREVIEW;
    }
}
