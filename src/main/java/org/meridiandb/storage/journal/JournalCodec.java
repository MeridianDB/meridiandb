// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.journal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.meridiandb.temporal.DatabaseId;
import org.meridiandb.temporal.EntityId;
import org.meridiandb.temporal.RequestId;
import org.meridiandb.temporal.Transaction;
import org.meridiandb.temporal.ValidTimeInterval;
import org.meridiandb.temporal.WriteOperation;

/**
 * Pure experimental journal v1 codec. No I/O, sync, repair or durable acknowledgement.
 * Decoders advance input only after success, independent of the caller's byte order.
 */
public final class JournalCodec {
    public static final int MAX_ID_CODE_UNITS = 32_768;
    public static final int MAX_OPERATIONS = 65_536;
    public static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final long FILE_MAGIC = 0x4d52444e4a4e4c00L;
    private static final int RECORD_MAGIC = 0x4d54584e;
    private static final int VERSION = 1;
    private static final int PREFIX_BYTES = 16;
    private static final int MIN_PAYLOAD_BYTES = 26;

    private JournalCodec() { }

    public static byte[] encodeHeader(DatabaseId databaseId) {
        Objects.requireNonNull(databaseId, "databaseId");
        int stringBytes = stringSize(databaseId.value());
        var output = ByteBuffer.allocate(12 + stringBytes + Integer.BYTES);
        output.putLong(FILE_MAGIC).putInt(VERSION);
        putString(output, databaseId.value());
        output.putInt(checksum(output, 0, output.position()));
        return output.array();
    }

    public static DatabaseId readHeader(ByteBuffer source) {
        var input = copy(source);
        int start = input.position();
        int size = headerBytes(input);
        int units = (size - 20) / Character.BYTES;
        if (input.remaining() < size) {
            throw failure(JournalFormatException.Code.INCOMPLETE_HEADER, "Incomplete lineage or header checksum");
        }
        input.position(start + PREFIX_BYTES);
        String lineage = characters(input, units);
        int end = input.position();
        if (input.getInt() != checksum(input, start, end - start)) {
            throw corrupt("Journal header checksum mismatch");
        }
        var database = new DatabaseId(lineage);
        source.position(input.position());
        return database;
    }

    /** Validate limits and compute exact frame bytes before allocating output. */
    public static int encodedRecordBytes(JournalRecord record) {
        Objects.requireNonNull(record, "record");
        var request = record.transaction();
        operationCount(request.operations().size());
        long size = Long.BYTES + stringSize(request.requestId().value()) + Integer.BYTES;
        for (var operation : request.operations()) {
            var interval = operation.interval();
            size += 2L + stringSize(operation.entity().value())
                    + (interval.fromInclusiveMicros() == null ? 0 : Long.BYTES)
                    + (interval.toExclusiveMicros() == null ? 0 : Long.BYTES)
                    + (operation instanceof WriteOperation.Put ? Long.BYTES : 0);
            if (size > MAX_PAYLOAD_BYTES) {
                throw failure(JournalFormatException.Code.LIMIT_EXCEEDED, "Transaction payload exceeds journal v1 limit");
            }
        }
        return PREFIX_BYTES + Math.toIntExact(size) + Integer.BYTES;
    }

    public static byte[] encodeRecord(JournalRecord record) {
        int payloadBytes = encodedRecordBytes(record) - PREFIX_BYTES - Integer.BYTES;
        var request = record.transaction();
        int count = request.operations().size();
        var output = ByteBuffer.allocate(PREFIX_BYTES + payloadBytes + Integer.BYTES);
        output.putInt(RECORD_MAGIC).putInt(VERSION).putInt(payloadBytes);
        output.putInt(checksum(output, 0, 12));
        output.putLong(record.sequence());
        putString(output, request.requestId().value());
        output.putInt(count);
        for (var operation : request.operations()) {
            output.put((byte) (operation instanceof WriteOperation.Put ? 1 : 2));
            putString(output, operation.entity().value());
            var interval = operation.interval();
            output.put((byte) ((interval.fromInclusiveMicros() == null ? 0 : 1)
                    | (interval.toExclusiveMicros() == null ? 0 : 2)));
            if (interval.fromInclusiveMicros() != null) {
                output.putLong(interval.fromInclusiveMicros());
            }
            if (interval.toExclusiveMicros() != null) {
                output.putLong(interval.toExclusiveMicros());
            }
            if (operation instanceof WriteOperation.Put put) {
                output.putLong(put.value());
            }
        }
        output.putInt(checksum(output, PREFIX_BYTES, payloadBytes));
        return output.array();
    }

    /** Empty means clean EOF; an incomplete candidate tail raises INCOMPLETE_TAIL. */
    public static Optional<JournalRecord> readRecord(ByteBuffer source) {
        var input = copy(source);
        int start = input.position();
        if (!input.hasRemaining()) {
            return Optional.empty();
        }
        int size = recordBytes(input);
        int length = size - PREFIX_BYTES - Integer.BYTES;
        if (input.remaining() < size) {
            throw incomplete();
        }
        input.position(start + PREFIX_BYTES);
        int payloadStart = input.position();
        if (input.getInt(payloadStart + length) != checksum(input, payloadStart, length)) {
            throw corrupt("Record payload checksum mismatch");
        }
        var payload = input.slice(payloadStart, length).order(ByteOrder.BIG_ENDIAN);
        JournalRecord record;
        try {
            long sequence = payload.getLong();
            var requestId = new RequestId(getString(payload));
            need(payload, Integer.BYTES);
            int count = operationCount(payload.getInt());
            if (count > payload.remaining() / 8) {
                throw corrupt("Operation count cannot fit in the payload");
            }
            var operations = new ArrayList<WriteOperation>(count);
            for (int i = 0; i < count; i++) {
                need(payload, 1);
                int kind = Byte.toUnsignedInt(payload.get());
                if (kind != 1 && kind != 2) {
                    throw corrupt("Unknown operation kind");
                }
                var entity = new EntityId(getString(payload));
                need(payload, 1);
                int flags = Byte.toUnsignedInt(payload.get());
                if ((flags & ~3) != 0) {
                    throw corrupt("Unknown endpoint flag");
                }
                Long from = (flags & 1) == 0 ? null : getLong(payload);
                Long to = (flags & 2) == 0 ? null : getLong(payload);
                var interval = new ValidTimeInterval(from, to);
                operations.add(kind == 1 ? new WriteOperation.Put(entity, interval, getLong(payload))
                        : new WriteOperation.Retract(entity, interval));
            }
            if (payload.hasRemaining()) {
                throw corrupt("Trailing bytes inside transaction payload");
            }
            record = new JournalRecord(sequence, new Transaction(requestId, operations));
        } catch (IllegalArgumentException invalid) {
            throw corrupt("Invalid temporal payload: " + invalid.getMessage());
        }
        source.position(payloadStart + length + Integer.BYTES);
        return Optional.of(record);
    }

    /** Validate an in-memory journal image; never repairs, skips corruption or establishes durability. */
    public static JournalScan scan(byte[] image) {
        var input = ByteBuffer.wrap(Objects.requireNonNull(image, "image"));
        var database = readHeader(input);
        var records = new ArrayList<JournalRecord>();
        var identities = new HashSet<RequestId>();
        long previous = 0;
        while (input.hasRemaining()) {
            JournalRecord record;
            try {
                record = readRecord(input).orElseThrow();
            } catch (JournalFormatException failure) {
                if (failure.code() == JournalFormatException.Code.INCOMPLETE_TAIL) {
                    return new JournalScan(database, records, input.position(), true);
                }
                throw failure;
            }
            if (previous == Long.MAX_VALUE || record.sequence() != previous + 1
                    || !identities.add(record.transaction().requestId())) {
                throw failure(JournalFormatException.Code.INVALID_HISTORY, "Noncontiguous sequence or reused request identity");
            }
            records.add(record);
            previous = record.sequence();
        }
        return new JournalScan(database, records, input.position(), false);
    }

    // Validate fixed prefixes before a file reader allocates their bounded bodies.
    static int headerBytes(ByteBuffer source) {
        var input = copy(source);
        if (input.remaining() < PREFIX_BYTES) {
            throw failure(JournalFormatException.Code.INCOMPLETE_HEADER, "Incomplete journal header");
        }
        if (input.getLong() != FILE_MAGIC) {
            throw corrupt("Journal magic does not match");
        }
        version(input.getInt());
        return 20 + stringLength(input.getInt()) * Character.BYTES;
    }

    static int recordBytes(ByteBuffer source) {
        var input = copy(source);
        int start = input.position();
        if (input.remaining() < PREFIX_BYTES) {
            throw incomplete();
        }
        int magic = input.getInt();
        int format = input.getInt();
        int length = input.getInt();
        if (input.getInt() != checksum(input, start, 12)) {
            throw corrupt("Record prefix checksum mismatch");
        }
        if (magic != RECORD_MAGIC) {
            throw corrupt("Record magic does not match");
        }
        version(format);
        if (length > MAX_PAYLOAD_BYTES) {
            throw failure(JournalFormatException.Code.LIMIT_EXCEEDED, "Record payload exceeds journal v1 limit");
        }
        if (length < MIN_PAYLOAD_BYTES) {
            throw corrupt("Record payload is too short");
        }
        return PREFIX_BYTES + length + Integer.BYTES;
    }

    private static ByteBuffer copy(ByteBuffer source) {
        return Objects.requireNonNull(source, "source").duplicate().order(ByteOrder.BIG_ENDIAN);
    }

    private static int stringSize(String value) {
        return Integer.BYTES + stringLength(value.length()) * Character.BYTES;
    }

    private static int stringLength(int length) {
        if (length <= 0) {
            throw corrupt("Identity must contain at least one UTF-16 code unit");
        }
        if (length > MAX_ID_CODE_UNITS) {
            throw failure(JournalFormatException.Code.LIMIT_EXCEEDED, "Identity exceeds journal v1 limit");
        }
        return length;
    }

    private static int operationCount(int count) {
        if (count <= 0) {
            throw corrupt("Transaction needs at least one operation");
        }
        if (count > MAX_OPERATIONS) {
            throw failure(JournalFormatException.Code.LIMIT_EXCEEDED, "Operation count exceeds journal v1 limit");
        }
        return count;
    }

    private static void putString(ByteBuffer output, String value) {
        output.putInt(value.length());
        for (int i = 0; i < value.length(); i++) {
            output.putChar(value.charAt(i));
        }
    }

    private static String characters(ByteBuffer input, int units) {
        var chars = new char[units];
        for (int i = 0; i < units; i++) {
            chars[i] = input.getChar();
        }
        return new String(chars);
    }

    private static String getString(ByteBuffer input) {
        need(input, Integer.BYTES);
        int units = stringLength(input.getInt());
        need(input, units * Character.BYTES);
        return characters(input, units);
    }

    private static long getLong(ByteBuffer input) {
        need(input, Long.BYTES);
        return input.getLong();
    }

    private static void need(ByteBuffer input, int bytes) {
        if (input.remaining() < bytes) {
            throw corrupt("Missing field inside complete transaction payload");
        }
    }

    private static int checksum(ByteBuffer input, int offset, int bytes) {
        var range = input.duplicate();
        range.position(offset).limit(offset + bytes);
        var checksum = new CRC32C();
        checksum.update(range);
        return (int) checksum.getValue();
    }

    private static void version(int version) {
        if (version != VERSION) {
            throw failure(JournalFormatException.Code.UNSUPPORTED_VERSION, "Unsupported journal version: " + version);
        }
    }

    private static JournalFormatException incomplete() {
        return failure(JournalFormatException.Code.INCOMPLETE_TAIL, "Incomplete trailing record candidate");
    }

    private static JournalFormatException corrupt(String message) {
        return failure(JournalFormatException.Code.CORRUPT, message);
    }

    private static JournalFormatException failure(JournalFormatException.Code code, String message) {
        return new JournalFormatException(code, message);
    }
}
