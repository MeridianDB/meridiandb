// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meridiandb.storage.manifest.CheckpointManifest;
import org.meridiandb.storage.manifest.ObjectCheckpointHead;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadCodec;
import org.meridiandb.storage.manifest.ObjectCheckpointHeadException;
import org.meridiandb.storage.object.ObjectId;
import org.meridiandb.storage.object.ObjectReference;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Pure type and native-stream faults supplement, rather than replace, actual SDK/HTTP checks. */
final class S3HeadTypesTest {
    private static final URI ENDPOINT = URI.create("https://objects.example.org");
    private static final String BUCKET = "bucket";
    private static final UUID NAMESPACE = new UUID(0, 1);
    private static final UUID PUBLISHER = new UUID(0, 2);
    private static final ObjectId LINEAGE = new ObjectId(
            "2b00012c047967ee602848bacc071ddffbe0ba95f58167c85d2122842a82ee8c");
    private static final S3HeadToken TOKEN = new S3HeadToken("\"opaque,head\\token\"");
    private static final ObjectCheckpointHead HEAD = new ObjectCheckpointHead(NAMESPACE, PUBLISHER,
            new UUID(0, 3), LINEAGE, 1,
            new ObjectReference(new ObjectId("bd068a065754e52824efaf91c44bc7234985e9a93c024dcb90e6951152e66982"), 136),
            new CheckpointManifest.JournalPrefix(0, 24, LINEAGE.value()), null);
    private static final byte[] IMAGE = ObjectCheckpointHeadCodec.encode(HEAD);

    @Test void strongOpaqueAsciiTokensPreserveExactBytesWithoutEscapeInterpretation() {
        for (String value : List.of("\"\"", "\"*\"", "\",\"", "\"a,b\"", "\"\\\"", "\"!#~\"", TOKEN.value())) {
            assertEquals(value, new S3HeadToken(value).value());
        }
    }

    @Test void tokenBoundIncludesTheTwoQuoteBytes() {
        assertEquals(1024, S3HeadToken.MAX_BYTES);
        String maximum = "\"" + "x".repeat(1022) + "\"";
        assertEquals(maximum, new S3HeadToken(maximum).value());
        assertThrows(IllegalArgumentException.class, () -> new S3HeadToken("\"" + "x".repeat(1023) + "\""));
    }

    @Test void tokensRejectWeakUnquotedWildcardListsAndExtraQuotes() {
        for (String value : List.of("", "*", "plain", "W/\"tag\"", "w/\"tag\"", " \"tag\"", "\"tag\" ",
                "\"one\", \"two\"", "\"one\",\"two\"", "\"one\"two\"", "\"one\\\"two\"", "\"")) {
            assertThrows(IllegalArgumentException.class, () -> new S3HeadToken(value), value);
        }
    }

    @Test void tokensRejectControlSpaceAndDelCharacters() {
        for (int value = 0; value <= 0x20; value++) {
            String token = "\"a" + (char) value + "b\"";
            assertThrows(IllegalArgumentException.class, () -> new S3HeadToken(token));
        }
        assertThrows(IllegalArgumentException.class, () -> new S3HeadToken("\"a\u007fb\""));
    }

    @Test void adapterAsciiPolicyRejectsObsTextUnicodeAndSurrogates() {
        for (int value = 0x80; value <= 0xff; value++) {
            String token = "\"" + (char) value + "\"";
            assertThrows(IllegalArgumentException.class, () -> new S3HeadToken(token));
        }
        for (String token : List.of("\"\u0100\"", "\"\u2603\"", "\"\ud800\"", "\"\ud83d\ude00\"")) {
            assertThrows(IllegalArgumentException.class, () -> new S3HeadToken(token));
        }
    }

    @Test void tokenNullIsRejectedExplicitly() {
        assertThrows(NullPointerException.class, () -> new S3HeadToken(null));
    }

    @Test void restoredObservationPreservesExactTargetImageReferenceAndToken() {
        var value = S3HeadObservation.restore(ENDPOINT, BUCKET, IMAGE, TOKEN);
        assertEquals(ENDPOINT, value.endpoint());
        assertEquals(BUCKET, value.bucket());
        assertEquals("ns-00000000000000000000000000000001-current.head", value.key().value());
        assertEquals(HEAD, value.head());
        assertEquals(ObjectReference.forBytes(IMAGE), value.reference());
        assertEquals(TOKEN, value.token());
        assertArrayEquals(IMAGE, value.image());
    }

    @Test void restoredObservationSnapshotsInputAndEveryReturnedImage() {
        byte[] input = IMAGE.clone();
        var observation = S3HeadObservation.restore(ENDPOINT, BUCKET, input, TOKEN);
        input[0] ^= 1;
        byte[] first = observation.image();
        byte[] second = observation.image();
        assertNotSame(first, second);
        first[first.length - 1] ^= 1;
        assertArrayEquals(IMAGE, second);
        assertArrayEquals(IMAGE, observation.image());
        assertEquals(HEAD, observation.head());
        assertEquals(ObjectReference.forBytes(IMAGE), observation.reference());
    }

    @Test void restoringADeclarationDoesNotResolveOrVerifyItsReferencedManifest() {
        var declaration = new ObjectCheckpointHead(NAMESPACE, PUBLISHER, new UUID(0, 4), LINEAGE, 1,
                new ObjectReference(new ObjectId("0".repeat(64)), 1), HEAD.journalPrefix(), null);
        var observation = S3HeadObservation.restore(ENDPOINT, BUCKET, ObjectCheckpointHeadCodec.encode(declaration), TOKEN);
        assertEquals(declaration, observation.head());
        assertEquals(1, observation.head().manifest().byteLength());
    }

    @Test void observationRejectsAnyNonexactHeadLength() {
        for (int length : new int[] {0, 1, 255, 257, 4096}) {
            assertThrows(IllegalArgumentException.class,
                    () -> S3HeadObservation.restore(ENDPOINT, BUCKET, new byte[length], TOKEN));
        }
    }

    @Test void observationRejectsDamagedHeadEvenWithAValidOpaqueToken() {
        byte[] corrupt = IMAGE.clone();
        corrupt[64] ^= 1;
        assertEquals(ObjectCheckpointHeadException.Code.CORRUPT,
                assertThrows(ObjectCheckpointHeadException.class,
                        () -> S3HeadObservation.restore(ENDPOINT, BUCKET, corrupt, TOKEN)).code());
    }

    @Test void observationRejectsNullTargetImageAndTokenArguments() {
        assertThrows(NullPointerException.class, () -> S3HeadObservation.restore(null, BUCKET, IMAGE, TOKEN));
        assertThrows(NullPointerException.class, () -> S3HeadObservation.restore(ENDPOINT, null, IMAGE, TOKEN));
        assertThrows(NullPointerException.class, () -> S3HeadObservation.restore(ENDPOINT, BUCKET, null, TOKEN));
        assertThrows(NullPointerException.class, () -> S3HeadObservation.restore(ENDPOINT, BUCKET, IMAGE, null));
    }

    @Test void observationAcceptsSupportedOriginsAndBoundaryBucketNamesWithoutCanonicalizingScope() {
        for (URI endpoint : List.of(ENDPOINT, URI.create("http://127.0.0.1:8766"), URI.create("http://127.0.0.1:8766/"))) {
            for (String bucket : List.of("abc", "x".repeat(63))) {
                var observation = S3HeadObservation.restore(endpoint, bucket, IMAGE, TOKEN);
                assertEquals(endpoint, observation.endpoint());
                assertEquals(bucket, observation.bucket());
            }
        }
    }

    @Test void observationRejectsUnsupportedEndpointOrigins() {
        for (String uri : List.of("file:///tmp", "relative", "http:opaque", "http://user@localhost", "http://localhost/path",
                "http://localhost?x=1", "http://localhost#part")) {
            assertThrows(IllegalArgumentException.class,
                    () -> S3HeadObservation.restore(URI.create(uri), BUCKET, IMAGE, TOKEN), uri);
        }
    }

    @Test void observationRejectsUnsupportedBucketNames() {
        for (String bucket : List.of("", "aa", "Bucket", "with.dot", "bucket--x-s3", "-bucket", "bucket-", "x".repeat(64))) {
            assertThrows(IllegalArgumentException.class,
                    () -> S3HeadObservation.restore(ENDPOINT, bucket, IMAGE, TOKEN), bucket);
        }
    }

    @Test void bindingsRejectNullAndNilIdentifiers() {
        var nil = new UUID(0, 0);
        assertThrows(NullPointerException.class, () -> new S3CheckpointHeads.Binding(null, PUBLISHER, LINEAGE));
        assertThrows(NullPointerException.class, () -> new S3CheckpointHeads.Binding(NAMESPACE, null, LINEAGE));
        assertThrows(NullPointerException.class, () -> new S3CheckpointHeads.Binding(NAMESPACE, PUBLISHER, null));
        assertThrows(IllegalArgumentException.class, () -> new S3CheckpointHeads.Binding(nil, PUBLISHER, LINEAGE));
        assertThrows(IllegalArgumentException.class, () -> new S3CheckpointHeads.Binding(NAMESPACE, nil, LINEAGE));
    }

    @Test void headKeyUsesAllUuidBitsAsLowercaseHexWithoutHyphens() {
        var namespace = UUID.fromString("ABCDEF01-2345-6789-ABCD-EF0123456789");
        var key = S3CheckpointHeads.headKey(namespace);
        assertEquals("ns-abcdef0123456789abcdef0123456789-current.head", key.value());
        assertEquals(48, key.value().length());
        assertThrows(NullPointerException.class, () -> S3CheckpointHeads.headKey(null));
        assertThrows(IllegalArgumentException.class, () -> S3CheckpointHeads.headKey(new UUID(0, 0)));
    }

    @Test void completePayloadClosesNativeStreamBeforeReturningSameResponseBytesAndToken() throws IOException {
        var bytes = new TrackedInput(IMAGE, false);
        var aborts = new AtomicInteger();
        var payload = S3CheckpointHeads.completeHead(response(), AbortableInputStream.create(bytes, aborts::incrementAndGet));
        assertArrayEquals(IMAGE, payload.image());
        assertEquals(TOKEN, payload.token());
        assertEquals(1, bytes.closes);
        assertEquals(0, aborts.get());
    }

    @Test void completePayloadDefersHeadCodecAndBindingValidationToTheReadCaller() throws IOException {
        byte[] malformed = new byte[256];
        var bytes = new TrackedInput(malformed, false);
        var payload = S3CheckpointHeads.completeHead(response(), AbortableInputStream.create(bytes));
        assertArrayEquals(malformed, payload.image());
        assertEquals(1, bytes.closes);
        assertThrows(ObjectCheckpointHeadException.class,
                () -> S3HeadObservation.restore(ENDPOINT, BUCKET, payload.image(), payload.token()));
    }

    @Test void missingDuplicateOrJoinedSdkEtagsExposeNoPayloadAfterKnownCleanup() {
        for (var tokens : List.of(List.<String>of(), List.of(TOKEN.value(), TOKEN.value()),
                List.of("\"first\"", "\"second\""), List.of("\"first\", \"second\""))) {
            var bytes = new TrackedInput(IMAGE, false);
            var result = response(200, 256L, null, List.of("256"), tokens);
            assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(result, AbortableInputStream.create(bytes)));
            assertEquals(1, bytes.closes);
        }
    }

    @Test void unsupportedSingleSdkTokenExposesNoPayloadAfterKnownCleanup() {
        for (String token : List.of("", "W/\"weak\"", "*", "unquoted", "\"\u00e9\"", "\"" + "x".repeat(1023) + "\"")) {
            var bytes = new TrackedInput(IMAGE, false);
            var result = response(200, 256L, null, List.of("256"), List.of(token));
            assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(result, AbortableInputStream.create(bytes)));
            assertEquals(1, bytes.closes);
        }
    }

    @Test void sdkVisibleLengthMustBeSingleAndCanonicalEvenWhenModeledLengthIsCorrect() {
        for (var lengths : List.of(List.<String>of(), List.of("0256"), List.of("256", "256"), List.of("256,256"))) {
            var bytes = new TrackedInput(IMAGE, false);
            var result = response(200, 256L, null, lengths, List.of(TOKEN.value()));
            assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(result, AbortableInputStream.create(bytes)));
            assertEquals(1, bytes.closes);
        }
    }

    @Test void modeledStatusRangeAndLengthFailuresAbortAndCloseBeforeReadingTheBody() {
        for (var result : List.of(
                response(206, 256L, null, List.of("256"), List.of(TOKEN.value())),
                response(200, 256L, "bytes 0-255/256", List.of("256"), List.of(TOKEN.value())),
                response(200, 255L, null, List.of("255"), List.of(TOKEN.value())),
                response(200, 257L, null, List.of("257"), List.of(TOKEN.value())),
                response(200, null, null, List.of(), List.of(TOKEN.value())))) {
            var bytes = new TrackedInput(IMAGE, false);
            var aborts = new AtomicInteger();
            assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(result, AbortableInputStream.create(bytes, aborts::incrementAndGet)));
            assertEquals(0, bytes.reads);
            assertEquals(1, bytes.closes);
            assertTrue(aborts.get() > 0);
        }
    }

    @Test void nativeCloseFailureReturnsNoPayloadAndAttemptsAbort() {
        var bytes = new TrackedInput(IMAGE, true);
        var aborts = new AtomicInteger();
        var failure = assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(response(),
                AbortableInputStream.create(bytes, aborts::incrementAndGet)));
        assertSame(bytes.closeFailure, failure.getCause());
        assertEquals(1, bytes.closes);
        assertTrue(aborts.get() > 0);
    }

    @Test void primaryShortReadRetainsCloseAndAbortFailures() {
        var bytes = new TrackedInput(new byte[255], true);
        var abortFailure = new IllegalStateException("injected head abort failure");
        var failure = assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(response(),
                AbortableInputStream.create(bytes, () -> { throw abortFailure; })));
        var primary = failure.getCause();
        assertTrue(primary.getMessage().contains("Short"));
        assertTrue(Arrays.asList(primary.getSuppressed()).contains(bytes.closeFailure));
        assertTrue(Arrays.asList(primary.getSuppressed()).contains(abortFailure));
        assertEquals(1, bytes.closes);
    }

    @Test void extraBodyByteReturnsNoPayloadAndAbortsBeforeClose() {
        var bytes = new TrackedInput(new byte[257], false);
        var aborts = new AtomicInteger();
        assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(response(),
                AbortableInputStream.create(bytes, aborts::incrementAndGet)));
        assertEquals(1, bytes.closes);
        assertTrue(aborts.get() > 0);
    }

    @Test void zeroProgressStreamFailsWithinABoundedReadCountAndCloses() {
        var bytes = new TrackedInput(IMAGE, false) {
            @Override public int read(byte[] target, int offset, int length) { reads++; return 0; }
        };
        var aborts = new AtomicInteger();
        assertThrows(IOException.class, () -> S3CheckpointHeads.completeHead(response(),
                AbortableInputStream.create(bytes, aborts::incrementAndGet)));
        assertTrue(bytes.reads > 0 && bytes.reads <= 16);
        assertEquals(1, bytes.closes);
        assertTrue(aborts.get() > 0);
    }

    private static GetObjectResponse response() {
        return response(200, 256L, null, List.of("256"), List.of(TOKEN.value()));
    }

    private static GetObjectResponse response(int status, Long length, String range, List<String> rawLengths, List<String> tokens) {
        var http = SdkHttpFullResponse.builder().statusCode(status);
        if (!rawLengths.isEmpty()) { http.putHeader("Content-Length", rawLengths); }
        if (!tokens.isEmpty()) { http.putHeader("ETag", tokens); }
        var response = GetObjectResponse.builder().eTag("\"modeled-token-is-not-the-source\"");
        if (length != null) { response.contentLength(length); }
        if (range != null) { response.contentRange(range); }
        return (GetObjectResponse) response.sdkHttpResponse(http.build()).build();
    }

    private static class TrackedInput extends ByteArrayInputStream {
        final IOException closeFailure = new IOException("injected head close failure");
        private final boolean failClose;
        int reads;
        int closes;

        TrackedInput(byte[] image, boolean failClose) { super(image); this.failClose = failClose; }

        @Override public int read(byte[] target, int offset, int length) {
            reads++;
            return super.read(target, offset, length);
        }

        @Override public int read() { reads++; return super.read(); }

        @Override public void close() throws IOException {
            closes++;
            if (failClose) { throw closeFailure; }
            super.close();
        }
    }
}
