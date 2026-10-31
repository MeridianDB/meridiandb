// SPDX-License-Identifier: Apache-2.0
package org.meridiandb.storage.s3;

import java.net.URI;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Shared configuration only; each adapter owns a separate client and serializes its own close. */
final class S3ClientOwner implements AutoCloseable {
    final URI endpoint;
    final String bucket;
    final S3Client client;
    private final BoundedHttpClient http;

    S3ClientOwner(URI endpoint, Region region, String bucket, AwsCredentialsProvider credentials, S3ObjectStore.Timeouts timeouts) {
        validateTarget(endpoint, bucket);
        this.endpoint = endpoint;
        this.bucket = bucket;
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(timeouts, "timeouts");
        http = new BoundedHttpClient(UrlConnectionHttpClient.builder()
                .connectionTimeout(timeouts.connect()).socketTimeout(timeouts.socket()).build());
        try {
            client = S3Client.builder().httpClient(http).endpointOverride(endpoint).region(region)
                    .credentialsProvider((AwsCredentialsProvider) credentials::resolveCredentials).forcePathStyle(true)
                    .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                    .overrideConfiguration(configuration -> configuration
                            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                            .apiCallTimeout(timeouts.operation()).apiCallAttemptTimeout(timeouts.operation()))
                    .build();
        } catch (RuntimeException | Error failure) {
            try { http.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
    }

    static void validateTarget(URI endpoint, String bucket) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(bucket, "bucket");
        if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") || bucket.endsWith("--x-s3")) {
            throw new IllegalArgumentException("Use a 3–63 character general-purpose bucket without dots");
        }
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))) {
            throw new IllegalArgumentException("Endpoint must be an absolute HTTP(S) origin");
        }
    }

    @Override public void close() {
        try { client.close(); }
        catch (RuntimeException | Error failure) {
            try { http.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
        http.close();
    }
}
