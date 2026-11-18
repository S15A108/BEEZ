package com.beez.backend.document.support;

import com.beez.backend.document.config.DocumentConfiguration;
import com.beez.backend.document.config.DocumentObjectStorageProperties;
import java.net.URI;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

public final class SeaweedTestContainer extends GenericContainer<SeaweedTestContainer> {
    public static final String BUCKET = "beez-documents";
    public static final String REGION = "us-east-1";
    public static final String ACCESS_KEY = "fixture-access";
    public static final String SECRET_KEY = "fixture-secret";

    private static SeaweedTestContainer shared;
    private static Throwable sharedStartFailure;

    public SeaweedTestContainer() {
        super(DockerImageName.parse("chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"));
        withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY).withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("S3_BUCKET", BUCKET).withExposedPorts(8333)
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
    }

    /**
     * One container per test JVM (S15P31A108-230). Starting it per class repeated the ~4s container start and the
     * 6-8s wait for the bucket. Returns only once the bucket answers, so tests need no wait of their own.
     * Never stopped explicitly; Testcontainers (Ryuk) removes it when the JVM exits, like the PostgreSQL container
     * in IntegrationTestSupport. The bucket is shared, so tests compare stored-object counts against their own
     * starting count only.
     * <p>
     * If the first start fails (any Throwable, Errors included), the container is stopped and the same failure is
     * rethrown to every later caller, so one broken start does not turn into a fresh container and another full wait
     * per test class. A failure while stopping is attached as suppressed, so the original cause is never lost and
     * the failure is still recorded.
     */
    public static synchronized SeaweedTestContainer shared() {
        if (sharedStartFailure != null) {
            throw new IllegalStateException("shared SeaweedFS container failed to start earlier", sharedStartFailure);
        }
        if (shared == null) {
            SeaweedTestContainer container = new SeaweedTestContainer();
            try {
                container.start();
                container.awaitBucket();
            } catch (Throwable e) {
                try {
                    container.stop();
                } catch (Throwable stopFailure) {
                    e.addSuppressed(stopFailure);
                }
                sharedStartFailure = e;
                // Precise rethrow: the try block only throws unchecked types, so this needs no throws clause.
                throw e;
            }
            shared = container;
        }
        return shared;
    }

    public String endpoint() { return "http://" + getHost() + ":" + getMappedPort(8333); }

    /** Storage properties pointing at this container, as the application would bind them. */
    public DocumentObjectStorageProperties storageProperties() {
        return new DocumentObjectStorageProperties(URI.create(endpoint()), REGION, BUCKET, ACCESS_KEY, SECRET_KEY);
    }

    /**
     * The S3_BUCKET bucket is created after the port opens, so a listening port alone is not enough.
     * Probes with the application's own client configuration (DocumentConfiguration.s3Client).
     */
    private void awaitBucket() {
        try (S3Client client = DocumentConfiguration.s3Client(storageProperties())) {
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).ignoreExceptions()
                    .until(() -> client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build()) != null);
        }
    }
}
