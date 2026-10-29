package com.beez.backend.document.support;

import java.net.URI;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

public final class SeaweedTestContainer extends GenericContainer<SeaweedTestContainer> {
    public static final String BUCKET = "beez-documents";
    public static final String ACCESS_KEY = "fixture-access";
    public static final String SECRET_KEY = "fixture-secret";

    private static SeaweedTestContainer shared;

    public SeaweedTestContainer() {
        super(DockerImageName.parse("chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"));
        withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY).withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("S3_BUCKET", BUCKET).withExposedPorts(8333)
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
    }

    /**
     * 통합 테스트 JVM 하나에서 한 번만 띄워 함께 쓰는 인스턴스 (S15P31A108-230). 클래스마다 띄우면 컨테이너 시작(약 4초)과
     * 버킷 준비 대기(6~8초)를 매번 다시 한다. 버킷이 응답할 때까지 기다린 뒤 돌려주므로 시험에서 따로 기다리지 않아도 된다.
     * 멈추지 않는다 — JVM 이 끝나면 Testcontainers(Ryuk)가 치운다 (IntegrationTestSupport 의 PostgreSQL 과 같은 방식).
     * 버킷을 함께 쓰므로, 저장된 원본 개수는 시험마다 시작 전 대비로만 비교한다.
     */
    public static synchronized SeaweedTestContainer shared() {
        if (shared == null) {
            SeaweedTestContainer container = new SeaweedTestContainer();
            container.start();
            container.awaitBucket();
            shared = container;
        }
        return shared;
    }

    public String endpoint() { return "http://" + getHost() + ":" + getMappedPort(8333); }

    /**
     * S3_BUCKET 의 버킷은 포트가 열린 뒤에 만들어져서({@code Wait.forListeningPort} 만으로는 부족), 버킷이 응답할 때까지 기다린다.
     * 클라이언트 설정은 본 코드(DocumentConfiguration)와 같게 해 시험이 쓰는 조건에서 확인한다.
     */
    private void awaitBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).ignoreExceptions()
                    .until(() -> client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build()) != null);
        }
    }
}
