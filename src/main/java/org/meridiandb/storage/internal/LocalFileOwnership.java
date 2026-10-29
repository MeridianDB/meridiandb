// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Internal cross-adapter reservation; acquire before open, release only after actual channel close. */
public final class LocalFileOwnership {
    private static final Set<Reservation> ACTIVE = new HashSet<>();
    private LocalFileOwnership() { }

    public static Reservation control(Path path) throws IOException { return reserve(path, true); }
    public static Reservation read(Path path) throws IOException { return reserve(path, false); }

    private static synchronized Reservation reserve(Path requested, boolean control) throws IOException {
        Path absolute = requested.toAbsolutePath();
        Path path = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        for (var active : ACTIVE) {
            if ((control || active.control) && (active.path.equals(path)
                    || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(path, active.path)))) {
                throw new IOException("Path is already owned by a local persistence adapter: " + path);
            }
        }
        var reserved = new Reservation(path, control);
        ACTIVE.add(reserved);
        return reserved;
    }

    public static OwnedChannel openControl(Path path, OpenOption... options) throws IOException {
        return open(path, true, options);
    }

    public static OwnedChannel openData(Path path, OpenOption... options) throws IOException {
        return open(path, false, options);
    }

    private static OwnedChannel open(Path path, boolean control, OpenOption... options) throws IOException {
        var reservation = reserve(path, control);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path, options);
            return new OwnedChannel(channel, reservation);
        } catch (IOException | RuntimeException | Error failure) {
            if (channel == null) { reservation.close(); }
            else {
                try { channel.close(); reservation.close(); }
                catch (IOException | RuntimeException | Error closeFailure) {
                    if (failure != closeFailure) { failure.addSuppressed(closeFailure); }
                }
            }
            throw failure;
        }
    }

    /** A native close failure remains uncertain even if AbstractInterruptibleChannel reports closed. */
    public static final class OwnedChannel implements AutoCloseable {
        private final FileChannel channel;
        private final Reservation reservation;
        private boolean closed;
        private Throwable uncertain;
        OwnedChannel(FileChannel channel, Reservation reservation) {
            this.channel = channel;
            this.reservation = reservation;
        }
        public FileChannel channel() { return channel; }
        public synchronized boolean isClosed() { return closed; }
        @Override public synchronized void close() throws IOException {
            if (closed) { return; }
            if (uncertain != null) { throw new IOException("Native channel cleanup is uncertain; restart the process", uncertain); }
            if (!channel.isOpen()) {
                uncertain = new IOException("Channel closed outside its ownership wrapper; cleanup is uncertain");
                throw (IOException) uncertain;
            }
            try { channel.close(); }
            catch (IOException | RuntimeException | Error failure) { uncertain = failure; throw failure; }
            closed = true;
            reservation.close();
        }
    }

    public static final class Reservation implements AutoCloseable {
        private final Path path;
        private final boolean control;
        private Reservation(Path path, boolean control) { this.path = path; this.control = control; }
        @Override public void close() { synchronized (LocalFileOwnership.class) { ACTIVE.remove(this); } }
    }
}
