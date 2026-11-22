package dev.geduu.albumjukebox;

public enum PlaybackMode {
    ONCE, ALL, ONE;

    public PlaybackMode next() {
        return switch (this) {
            case ONCE -> ALL;
            case ALL -> ONE;
            case ONE -> ONCE;
        };
    }

    public static PlaybackMode fromId(int id) {
        return switch (id) {
            case 1 -> ALL;
            case 2 -> ONE;
            default -> ONCE;
        };
    }
}
