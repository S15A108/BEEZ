package com.beez.backend.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The provisional schema must not run in the default Flyway location. */
class DraftMigrationResourceTest {
    @Test
    void draftIsAvailableOnlyViaItsExplicitLocation() {
        String file = "V8__design_dependency_confirmation.sql";
        assertThat(new ClassPathResource("db/migration/" + file).exists()).isFalse();
        assertThat(new ClassPathResource("db/ontology-draft/" + file).exists()).isTrue();
    }
}