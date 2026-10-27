package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.document.config.DocumentObjectStorageProperties;
import com.beez.backend.document.storage.S3DocumentStorage;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3DocumentStorageTest {
    final S3Client client = mock(S3Client.class);
    final S3DocumentStorage storage = new S3DocumentStorage(client, "beez-documents");

    @Test void preservesOriginalBytesAndUsesStableUuidKeys() throws Exception {
        byte[] bytes = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 65, 13, 10};
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().eTag("fixture").build());
        String key = storage.store(bytes, "md");
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(key).matches("[a-f0-9-]{36}\\.md");
        assertThat(request.getValue().bucket()).isEqualTo("beez-documents");
        assertThat(request.getValue().key()).isEqualTo(key);
        assertThat(request.getValue().contentType()).isEqualTo("text/markdown");
        try (var stream = body.getValue().contentStreamProvider().newStream()) { assertThat(stream.readAllBytes()).containsExactly(bytes); }
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
        assertThat(storage.read(key)).containsExactly(bytes);
        storage.delete(key);
        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void rejectsTraversalAndUnvalidatedExtensionBeforeAnyNetworkCall() {
        for (String key : new String[] {"../other.md", "s3://bucket/file", "https://host/file", "/absolute.md"}) {
            assertThatThrownBy(() -> storage.read(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> storage.store(new byte[] {65}, "sql")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test void doesNotReportSuccessAfterStorageFailure() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());
        assertThatThrownBy(() -> storage.store(new byte[] {65}, "txt")).isInstanceOf(IOException.class);
    }

    @Test void configurationRejectsMissingCredentialsAndNeverPrintsSecrets() {
        var props = new DocumentObjectStorageProperties(
                URI.create("http://localhost:8333"), "us-east-1", "beez-documents", "fixture-access", "fixture-secret");
        assertThat(props.toString()).doesNotContain("fixture-access", "fixture-secret");
        assertThatThrownBy(() -> new DocumentObjectStorageProperties(
                URI.create("http://localhost:8333"), "us-east-1", "beez-documents", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentObjectStorageProperties(
                URI.create("http://user:secret@host:8333"), "us-east-1", "beez-documents", "fixture", "fixture"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
