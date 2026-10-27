package com.beez.backend.document.storage;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Private SeaweedFS bucket via S3 API; opaque UUID keys are shared with document_version. */
public final class S3DocumentStorage implements DocumentStorage {
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(md|txt)");
    private final S3Client client;
    private final String bucket;

    public S3DocumentStorage(S3Client client, String bucket) {
        this.client = java.util.Objects.requireNonNull(client);
        this.bucket = java.util.Objects.requireNonNull(bucket);
    }

    @Override public String store(byte[] bytes, String extension) throws IOException {
        if (!"md".equals(extension) && !"txt".equals(extension)) throw new IllegalArgumentException("Validated md/txt extension required");
        String key = UUID.randomUUID() + "." + extension;
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType(extension.equals("md") ? "text/markdown" : "text/plain").build(), RequestBody.fromBytes(bytes));
            return key;
        } catch (SdkException error) { throw new IOException("Original object storage failed", error); }
    }

    @Override public byte[] read(String key) throws IOException {
        validateKey(key);
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (SdkException error) { throw new IOException("Original object unavailable", error); }
    }

    @Override public void delete(String key) throws IOException {
        validateKey(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception error) {
            if (error.statusCode() == 404 && error.awsErrorDetails() != null
                    && "NoSuchKey".equals(error.awsErrorDetails().errorCode())) return;
            throw new IOException("Original object deletion failed", error);
        } catch (SdkException error) { throw new IOException("Original object deletion failed", error); }
    }

    private static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid storage key");
    }
}
