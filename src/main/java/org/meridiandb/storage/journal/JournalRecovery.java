// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.RequestId;

/** Sequential recovery: at most one bounded frame, plus the exact request-ID set. */
final class JournalRecovery {
    record Result(DatabaseId databaseId, long sequence, long validBytes, long fileBytes,
                  Set<RequestId> identities) {
        boolean incomplete() { return validBytes != fileBytes; }
    }

    private JournalRecovery() { }

    static Result scan(LocalJournal.FileAccess file, Consumer<? super JournalRecord> consumer) throws IOException {
        long size = file.size();
        var header = header(file, size);
        var database = header.databaseId();
        long offset = header.bytes().length;
        var prefix = ByteBuffer.allocate(16);
        long previous = 0;
        var identities = new HashSet<RequestId>();
        while (offset < size) {
            if (size - offset < 16) {
                break;
            }
            prefix.clear();
            readFully(file, prefix, offset);
            prefix.flip();
            int frameSize = JournalCodec.recordBytes(prefix);
            if (size - offset < frameSize) {
                break;
            }
            var frame = ByteBuffer.allocate(frameSize);
            frame.put(prefix);
            readFully(file, frame, offset + 16);
            frame.flip();
            var record = JournalCodec.readRecord(frame).orElseThrow();
            if (previous == Long.MAX_VALUE || record.sequence() != previous + 1
                    || !identities.add(record.transaction().requestId())) {
                throw new JournalFormatException(JournalFormatException.Code.INVALID_HISTORY,
                        "Noncontiguous sequence or reused request identity at byte " + offset);
            }
            previous = record.sequence();
            offset += frameSize;
            consumer.accept(record);
        }
        if (file.size() != size) {
            throw new IOException("Journal size changed during recovery");
        }
        return new Result(database, previous, offset, size, identities);
    }

    /** Hash only the requested complete history using the already owned file access. */
    static LocalJournal.Prefix prefix(LocalJournal.FileAccess file, DatabaseId databaseId,
                                     long throughSequence) throws IOException {
        long size = file.size();
        var header = header(file, size);
        if (!header.databaseId().equals(databaseId)) {
            throw new JournalFormatException(JournalFormatException.Code.INVALID_HISTORY,
                    "Journal lineage changed during prefix verification");
        }
        var digest = sha256();
        digest.update(header.bytes());
        long offset = header.bytes().length;
        long previous = 0;
        var identities = new HashSet<RequestId>();
        var fixed = ByteBuffer.allocate(16);
        while (previous < throughSequence) {
            if (size - offset < 16) {
                throw new JournalFormatException(JournalFormatException.Code.INCOMPLETE_TAIL,
                        "Incomplete journal prefix frame at byte " + offset);
            }
            fixed.clear();
            readFully(file, fixed, offset);
            fixed.flip();
            int frameSize = JournalCodec.recordBytes(fixed);
            if (size - offset < frameSize) {
                throw new JournalFormatException(JournalFormatException.Code.INCOMPLETE_TAIL,
                        "Incomplete journal prefix frame at byte " + offset);
            }
            var frame = ByteBuffer.allocate(frameSize);
            frame.put(fixed);
            readFully(file, frame, offset + 16);
            frame.flip();
            var record = JournalCodec.readRecord(frame).orElseThrow();
            if (record.sequence() != previous + 1 || !identities.add(record.transaction().requestId())) {
                throw new JournalFormatException(JournalFormatException.Code.INVALID_HISTORY,
                        "Noncontiguous sequence or reused request identity at byte " + offset);
            }
            digest.update(frame.array());
            previous = record.sequence();
            offset += frameSize;
        }
        return new LocalJournal.Prefix(throughSequence, offset, HexFormat.of().formatHex(digest.digest()));
    }

    private record Header(DatabaseId databaseId, byte[] bytes) { }

    private static Header header(LocalJournal.FileAccess file, long size) throws IOException {
        if (size < 16) {
            throw new JournalFormatException(JournalFormatException.Code.INCOMPLETE_HEADER, "Incomplete journal header");
        }
        var prefix = ByteBuffer.allocate(16);
        readFully(file, prefix, 0);
        prefix.flip();
        int headerSize = JournalCodec.headerBytes(prefix);
        if (size < headerSize) {
            throw new JournalFormatException(JournalFormatException.Code.INCOMPLETE_HEADER, "Incomplete lineage or header checksum");
        }
        var header = ByteBuffer.allocate(headerSize);
        header.put(prefix);
        readFully(file, header, 16);
        header.flip();
        return new Header(JournalCodec.readHeader(header), header.array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", unavailable);
        }
    }

    static void readFully(LocalJournal.FileAccess file, ByteBuffer target, long offset) throws IOException {
        int zeros = 0;
        while (target.hasRemaining()) {
            int n = file.read(target, offset);
            if (n < 0) {
                throw new EOFException("Journal changed or ended during read at byte " + offset);
            }
            if (n == 0) {
                if (++zeros >= 16) {
                    throw new IOException("Journal read made no progress at byte " + offset);
                }
            } else {
                offset += n;
                zeros = 0;
            }
        }
    }

    static void writeFully(LocalJournal.FileAccess file, ByteBuffer source, long offset) throws IOException {
        int zeros = 0;
        while (source.hasRemaining()) {
            int n = file.write(source, offset);
            if (n < 0 || (n == 0 && ++zeros >= 16)) {
                throw new IOException("Journal write made no progress at byte " + offset);
            }
            if (n > 0) {
                offset += n;
                zeros = 0;
            }
        }
    }
}
