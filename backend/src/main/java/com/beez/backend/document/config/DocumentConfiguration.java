package com.beez.backend.document.config;

import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.storage.S3DocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({DocumentProperties.class, DocumentObjectStorageProperties.class})
public class DocumentConfiguration {
    @Bean DocumentInputValidator documentInputValidator() { return new DocumentInputValidator(1_048_576, 100_000); }
    @Bean DocumentStorage documentStorage(DocumentObjectStorageProperties objects,
            software.amazon.awssdk.services.s3.S3Client client) {
        return new S3DocumentStorage(client, objects.bucket());
    }

    @Bean(destroyMethod = "close")
    software.amazon.awssdk.services.s3.S3Client documentS3Client(DocumentObjectStorageProperties properties) {
        return s3Client(properties);
    }

    /** The one place the SeaweedFS client is configured; tests (SeaweedTestContainer) reuse it to probe readiness. */
    public static software.amazon.awssdk.services.s3.S3Client s3Client(DocumentObjectStorageProperties properties) {
        return software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(software.amazon.awssdk.regions.Region.of(properties.region()))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder()
                        .connectionTimeout(java.time.Duration.ofSeconds(3)).socketTimeout(java.time.Duration.ofSeconds(20)))
                .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(30)))
                .build();
    }
}
