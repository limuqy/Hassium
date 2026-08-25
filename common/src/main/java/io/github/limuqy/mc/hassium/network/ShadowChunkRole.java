package io.github.limuqy.mc.hassium.network;

/**
 * Identifies whether a stripped full-chunk payload is renderable or exists only
 * to provide the shadow server's one-chunk lighting boundary.
 */
public enum ShadowChunkRole {
    VISIBLE((byte) 0),
    HALO((byte) 1);

    private final byte wireValue;

    ShadowChunkRole(byte wireValue) {
        this.wireValue = wireValue;
    }

    public byte wireValue() {
        return wireValue;
    }

    public static ShadowChunkRole fromWire(byte wireValue) {
        return switch (wireValue) {
            case 0 -> VISIBLE;
            case 1 -> HALO;
            default -> throw new IllegalArgumentException("Unknown shadow chunk role: " + wireValue);
        };
    }
}
