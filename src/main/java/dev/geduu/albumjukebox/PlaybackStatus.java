package dev.geduu.albumjukebox;

public enum PlaybackStatus {
    STOPPED, LOADING, PLAYING, PAUSED, FINISHED, UNAVAILABLE, ERROR;

    public static PlaybackStatus fromId(int id) {
        return id >= 0 && id < values().length ? values()[id] : STOPPED;
    }
}
