// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
@SuppressWarnings("try")
class LocalIoObservationTest {
    @TempDir Path directory;

    @Test
    void literalRowsRetainNamedCountersAndExactCrossRoleSums() {
        assertArrayEquals(new String[] {"READ_ATTEMPTS", "READ_RETURNS", "READ_BYTES", "READ_ZERO", "READ_EOF", "READ_FAILURES",
                "WRITE_ATTEMPTS", "WRITE_RETURNS", "WRITE_BYTES", "WRITE_ZERO", "WRITE_FAILURES",
                "FORCE_ATTEMPTS", "FORCE_SUCCESSES", "FORCE_FAILURES"},
                java.util.Arrays.stream(LocalIoObservation.Metric.values()).map(Enum::name).toArray(String[]::new));
        var rows = emptyRows();
        rows.set(0, row(4, 4, 7, 1, 1, 0, 3, 3, 5, 1, 0, 2, 2, 0));
        rows.set(1, row(1, 1, 3, 0, 0, 0, 1, 1, 2, 0, 0, 1, 1, 0));
        var snapshot = new LocalIoObservation.Snapshot(1, false, false, rows);
        assertTrue(snapshot.complete(), "Zero-progress and EOF are known returns, not coverage failures");
        assertEquals(7, snapshot.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_BYTES));
        assertEquals(2, snapshot.value(LocalIo.Role.SEGMENT, LocalIoObservation.Metric.WRITE_BYTES));
        assertArrayEquals(new long[] {5, 5, 10, 1, 1, 0, 4, 4, 7, 1, 0, 3, 3, 0},
                java.util.Arrays.stream(LocalIoObservation.Metric.values()).mapToLong(snapshot::sum).toArray());
    }

    @Test
    void snapshotRejectsUnknownVersionsMalformedDimensionsNullsAndNegativeCounters() {
        assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(0, false, false, emptyRows()));
        assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(2, false, false, emptyRows()));
        assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(1, false, false, null));
        var shortRoles = emptyRows(); shortRoles.removeLast();
        assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(1, false, false, shortRoles));
        var extraRoles = emptyRows(); extraRoles.add(row(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(1, false, false, extraRoles));
        for (List<Long> malformed : java.util.Arrays.asList(null, List.of(0L),
                row(-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))) {
            var rows = emptyRows(); rows.set(0, malformed);
            assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(1, true, true, rows));
        }
        var nullValue = row(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); nullValue.set(2, null);
        var rows = emptyRows(); rows.set(0, nullValue);
        assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(1, false, false, rows));
    }

    @Test
    void healthySnapshotsRequireCoherentAttemptsReturnsAndPositiveByteCounts() {
        var malformed = List.of(
                row(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                row(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0),
                row(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0),
                row(1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                row(0, 0, 0, 0, 0, 0, 1, 1, 0, 2, 0, 0, 0, 0),
                row(2, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                row(0, 0, 0, 0, 0, 0, 2, 2, 1, 0, 0, 0, 0, 0),
                row(1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                row(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0));
        for (var bad : malformed) {
            var rows = emptyRows(); rows.set(0, bad);
            assertThrows(IllegalArgumentException.class, () -> new LocalIoObservation.Snapshot(1, false, false, rows));
        }
        var tooLarge = emptyRows();
        tooLarge.set(0, row(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(ArithmeticException.class, () -> new LocalIoObservation.Snapshot(1, false, false, tooLarge));
    }

    @Test
    void incompleteEvidencePreservesFailuresUnknownsAndRawInvalidOrOverflowValues() {
        for (var failed : List.of(
                row(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0),
                row(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0),
                row(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1))) {
            var rows = emptyRows(); rows.set(0, failed);
            var snapshot = new LocalIoObservation.Snapshot(1, false, false, rows);
            assertFalse(snapshot.complete());
            assertEquals(failed, snapshot.counts().get(0));
        }
        var unknown = emptyRows(); unknown.set(7, row(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertFalse(new LocalIoObservation.Snapshot(1, false, false, unknown).complete());
        var inconsistent = emptyRows(); inconsistent.set(0, row(1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        var overflow = new LocalIoObservation.Snapshot(1, true, false, inconsistent);
        var invalid = new LocalIoObservation.Snapshot(1, false, true, inconsistent);
        assertFalse(overflow.complete()); assertFalse(invalid.complete());
        assertTrue(overflow.overflow()); assertTrue(invalid.invalidResult());
        assertEquals(2, overflow.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_RETURNS));
        assertEquals(3, invalid.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_BYTES));
    }

    @Test
    void sumsRejectCrossRoleOverflowRatherThanReturningSaturatedOrWrappedValues() {
        var rows = emptyRows();
        rows.set(0, row(1, 1, Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        rows.set(1, row(1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        var snapshot = new LocalIoObservation.Snapshot(1, false, false, rows);
        assertThrows(ArithmeticException.class, () -> snapshot.sum(LocalIoObservation.Metric.READ_BYTES));
        assertEquals(2, snapshot.sum(LocalIoObservation.Metric.READ_RETURNS));
        assertEquals(Long.MAX_VALUE, snapshot.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_BYTES));
    }

    @Test
    void publicSnapshotsDefensivelyCopyBothLevelsAndExposeOnlyImmutableLists() {
        var rows = emptyRows();
        rows.set(0, row(1, 1, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        var snapshot = new LocalIoObservation.Snapshot(1, false, false, rows);
        rows.get(0).set(2, 99L); rows.clear();
        assertEquals(3, snapshot.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_BYTES));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().get(0).set(2, 7L));
    }

    @Test
    void bridgeExportsActualNativeBytesZeroEofAndForceWithoutOwningTheChannel() throws IOException {
        try (var channel = channel("native")) {
            var observation = LocalIoObservation.open();
            try (observation) {
                var source = ByteBuffer.wrap(new byte[] {2, 3, 5, 7, 11});
                while (source.hasRemaining()) {
                    assertTrue(LocalIo.write(LocalIo.Role.JOURNAL, channel, source, source.position()) > 0);
                }
                var target = ByteBuffer.allocate(3);
                while (target.hasRemaining()) {
                    assertTrue(LocalIo.read(LocalIo.Role.JOURNAL, channel, target, 1L + target.position()) > 0);
                }
                assertArrayEquals(new byte[] {3, 5, 7}, target.array());
                assertEquals(0, LocalIo.read(LocalIo.Role.JOURNAL, channel, ByteBuffer.allocate(0), 0));
                assertEquals(-1, LocalIo.read(LocalIo.Role.JOURNAL, channel, ByteBuffer.allocate(1), 5));
                LocalIo.force(LocalIo.Role.JOURNAL, channel, true);
            }
            var snapshot = observation.snapshot();
            assertTrue(snapshot.complete());
            assertEquals(5, snapshot.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.WRITE_BYTES));
            assertEquals(3, snapshot.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_BYTES));
            assertEquals(1, snapshot.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_ZERO));
            assertEquals(1, snapshot.value(LocalIo.Role.JOURNAL, LocalIoObservation.Metric.READ_EOF));
            assertEquals(1, snapshot.sum(LocalIoObservation.Metric.FORCE_SUCCESSES));
            assertTrue(channel.isOpen(), "Capture close does not close the storage owner");
            LocalIo.force(LocalIo.Role.JOURNAL, channel, true);
            assertEquals(snapshot, observation.snapshot(), "Later uncaptured work leaves the snapshot unchanged");
        }
    }

    @Test
    void bridgeRetainsNativeFailuresAndFaultLimitedOverflowAsIncompleteEvidence() throws IOException {
        var failed = LocalIoObservation.open();
        try (failed; var channel = channel("failed")) {
            var source = ByteBuffer.wrap(new byte[] {1, 2, 3});
            while (source.hasRemaining()) {
                assertTrue(LocalIo.write(LocalIo.Role.SEGMENT, channel, source, source.position()) > 0);
            }
            channel.close();
            assertThrows(ClosedChannelException.class, () -> LocalIo.read(LocalIo.Role.SEGMENT, channel, ByteBuffer.allocate(1), 0));
            assertThrows(ClosedChannelException.class, () -> LocalIo.write(LocalIo.Role.SEGMENT, channel, ByteBuffer.wrap(new byte[] {4}), 3));
            assertThrows(ClosedChannelException.class, () -> LocalIo.force(LocalIo.Role.SEGMENT, channel, true));
        }
        var failedSnapshot = failed.snapshot();
        assertFalse(failedSnapshot.complete());
        assertFalse(failedSnapshot.overflow()); assertFalse(failedSnapshot.invalidResult());
        assertEquals(3, failedSnapshot.value(LocalIo.Role.SEGMENT, LocalIoObservation.Metric.WRITE_BYTES));
        assertEquals(1, failedSnapshot.sum(LocalIoObservation.Metric.READ_FAILURES));
        assertEquals(1, failedSnapshot.sum(LocalIoObservation.Metric.WRITE_FAILURES));
        assertEquals(1, failedSnapshot.sum(LocalIoObservation.Metric.FORCE_FAILURES));
        var limited = LocalIoObservation.open(2);
        try (limited; var channel = channel("limited")) {
            var source = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
            while (source.hasRemaining()) {
                assertTrue(LocalIo.write(LocalIo.Role.JOURNAL, channel, source, source.position()) > 0);
            }
            assertEquals(5, channel.size(), "Telemetry saturation leaves actual storage work intact");
        }
        var limitedSnapshot = limited.snapshot();
        assertTrue(limitedSnapshot.overflow()); assertFalse(limitedSnapshot.complete());
        assertEquals(2, limitedSnapshot.sum(LocalIoObservation.Metric.WRITE_BYTES));
    }

    @Test
    void bridgePreservesOwnerThreadAndCloseBeforeSnapshotLifetime() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> LocalIoObservation.open(0));
        var observation = LocalIoObservation.open();
        try (observation) {
            assertThrows(IllegalStateException.class, observation::snapshot);
            assertThrows(IllegalStateException.class, LocalIoObservation::open);
            var task = new FutureTask<Void>(() -> {
                assertThrows(IllegalStateException.class, observation::close);
                assertThrows(IllegalStateException.class, observation::snapshot);
                return null;
            });
            Thread.ofPlatform().start(task);
            task.get(5, TimeUnit.SECONDS);
        }
        observation.close();
        assertTrue(observation.snapshot().complete());
        assertEquals(0, observation.snapshot().sum(LocalIoObservation.Metric.READ_ATTEMPTS));
    }

    private FileChannel channel(String name) throws IOException {
        return FileChannel.open(directory.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static List<List<Long>> emptyRows() {
        var rows = new ArrayList<List<Long>>(8);
        for (int i = 0; i < 8; i++) { rows.add(row(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)); }
        return rows;
    }

    private static List<Long> row(long... values) {
        var row = new ArrayList<Long>(values.length);
        for (long value : values) { row.add(value); }
        return row;
    }
}
