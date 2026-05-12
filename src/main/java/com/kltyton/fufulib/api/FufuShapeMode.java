package com.kltyton.fufulib.api;

public enum FufuShapeMode {
    BLOCK,
    BOUNDS,
    FULL;

    public static FufuShapeMode fromConfig(String raw) {
        if (raw == null) {
            return FULL;
        }
        return switch (raw.trim().toLowerCase()) {
            case "block", "none" -> BLOCK;
            case "bounds", "bound" -> BOUNDS;
            default -> FULL;
        };
    }
}

