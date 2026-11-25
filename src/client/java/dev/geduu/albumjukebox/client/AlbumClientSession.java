package dev.geduu.albumjukebox.client;

/** Presentation preferences for this client's current connection, never saved or sent to the server. */
final class AlbumClientSession {
    private static boolean peakHold = true;
    private static boolean showList;
    private static boolean showVfd;

    private AlbumClientSession() {}

    static boolean peakHold() { return peakHold; }
    static boolean showList() { return showList; }
    static boolean showVfd() { return showVfd; }

    static void togglePeakHold() { peakHold = !peakHold; }

    static void rememberView(boolean list, boolean vfd) {
        showList = list;
        showVfd = vfd;
    }

    static void reset() {
        peakHold = true;
        showList = false;
        showVfd = false;
    }
}
