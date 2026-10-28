package com.beez.backend.document.support;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public final class SeaweedTestContainer extends GenericContainer<SeaweedTestContainer> {
    public static final String BUCKET = "beez-documents";
    public static final String ACCESS_KEY = "fixture-access";
    public static final String SECRET_KEY = "fixture-secret";
    public SeaweedTestContainer() {
        super(DockerImageName.parse("chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"));
        withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY).withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
                .withEnv("S3_BUCKET", BUCKET).withExposedPorts(8333)
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));
    }
    public String endpoint() { return "http://" + getHost() + ":" + getMappedPort(8333); }
}
