package dev.geduu.albumjukebox;

import java.util.UUID;

public record AlbumVisualState(boolean hasDisc, int bodyColor, int labelColor, int discChange, Arm arm, UUID channel,
                               int cueChange, int positionFrames, int durationFrames, int trackNumber) {
    static final AlbumVisualState EMPTY = new AlbumVisualState(false, DiscColors.DEFAULT_BODY,
        DiscColors.DEFAULT_LABEL, 0, Arm.REST, null, 0, 0, 0, 0);

    public enum Arm {
        REST, HOVER, PLAY;

        static Arm fromId(int id) {
            return switch (id) {
                case 1 -> HOVER;
                case 2 -> PLAY;
                default -> REST;
            };
        }
    }
}
