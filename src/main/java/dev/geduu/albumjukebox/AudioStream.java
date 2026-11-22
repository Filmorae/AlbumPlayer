package dev.geduu.albumjukebox;

import java.util.concurrent.locks.LockSupport;

final class AudioStream {
    private static final long FRAME_NANOS = AlbumMenu.FRAME_MILLIS * 1_000_000L;

    interface Sink {
        void send(byte[] frame);
        void close();
    }

    enum End { ACTIVE, COMPLETE, INTERRUPTED, FAILED }

    private final Sink sink;
    private final byte[][] frames;
    private final Thread worker;
    private volatile int position;
    private volatile End end = End.ACTIVE;

    AudioStream(Sink sink, byte[][] frames, int start) {
        this.sink = sink;
        this.frames = frames;
        position = start;
        worker = Thread.ofVirtual().name("Album Jukebox audio").unstarted(this::play);
    }

    void start() {
        worker.start();
    }

    int position() {
        return position;
    }

    End end() {
        return end;
    }

    synchronized void stop() {
        finish(End.INTERRUPTED);
        worker.interrupt();
    }

    private void play() {
        long deadline = System.nanoTime();
        try {
            while (end == End.ACTIVE) {
                synchronized (this) {
                    if (end != End.ACTIVE) {
                        return;
                    }
                    if (position == frames.length) {
                        finish(End.COMPLETE);
                        return;
                    }
                    sink.send(frames[position]);
                    position++;
                }
                long now = System.nanoTime();
                deadline = Math.max(now, deadline + FRAME_NANOS);
                for (long wait = deadline - now; wait > 0 && end == End.ACTIVE; wait = deadline - System.nanoTime()) {
                    LockSupport.parkNanos(wait);
                }
            }
        } catch (RuntimeException exception) {
            AlbumJukebox.LOGGER.warn("Album Jukebox could not stream audio", exception);
            synchronized (this) {
                finish(End.FAILED);
            }
        }
    }

    private void finish(End reason) {
        if (end != End.ACTIVE) {
            return;
        }
        try {
            sink.close();
        } catch (RuntimeException exception) {
            AlbumJukebox.LOGGER.warn("Album Jukebox could not close an audio channel", exception);
        } finally {
            end = reason;
        }
    }
}
