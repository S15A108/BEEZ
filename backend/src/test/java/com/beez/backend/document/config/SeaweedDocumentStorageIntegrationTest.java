package com.beez.backend.document.config;

import static org.assertj.core.api.Assertions.*;

import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.storage.S3DocumentStorage;
import com.beez.backend.document.support.SeaweedTestContainer;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Tag("integration")
@Testcontainers
class SeaweedDocumentStorageIntegrationTest {
    @Container static final SeaweedTestContainer seaweed = new SeaweedTestContainer();

    @Test void authenticatedSeaweedRoundtripAndExistingKeyReadUseConfiguredBackend() {
        String endpoint = "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333);
        new ApplicationContextRunner().withUserConfiguration(DocumentConfiguration.class).withPropertyValues(
                "beez.document.worker-enabled=false", "beez.document.poll-millis=2000",
                "beez.document.embedding-dimension=1024", "beez.document.embedding-profile=unconfigured",
                "beez.document.embedding-model=unconfigured",
                "beez.document.object-storage.endpoint=" + endpoint, "beez.document.object-storage.region=us-east-1",
                "beez.document.object-storage.bucket=beez-documents", "beez.document.object-storage.access-key=fixture-access",
                "beez.document.object-storage.secret-key=fixture-secret").run(context -> {
            assertThat(context).hasNotFailed();
            var storage = context.getBean(DocumentStorage.class);
            assertThat(storage).isInstanceOf(S3DocumentStorage.class);
            var client = context.getBean(S3Client.class);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                    assertThat(client.headBucket(HeadBucketRequest.builder().bucket("beez-documents").build())).isNotNull());
            byte[] original = "\uFEFF# 문서\r\n원본을 보존한다.\r\n".getBytes(StandardCharsets.UTF_8);
            String key = storage.store(original, "md");
            assertThat(storage.read(key)).containsExactly(original);
            var unsigned = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint + "/beez-documents/" + key)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unsigned.statusCode()).isEqualTo(403);
            // Migration retains existing UUID keys; no document_version rewrite is needed.
            String migratedKey = UUID.randomUUID() + ".txt";
            client.putObject(PutObjectRequest.builder().bucket("beez-documents").key(migratedKey).build(), RequestBody.fromBytes(original));
            assertThat(storage.read(migratedKey)).containsExactly(original);
            storage.delete(migratedKey); storage.delete(migratedKey);
            storage.delete(key);
            assertThatThrownBy(() -> storage.read(key)).isInstanceOf(java.io.IOException.class);
        });
    }
}
