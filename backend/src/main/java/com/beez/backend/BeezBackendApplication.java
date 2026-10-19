package com.beez.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan   // beez.* → BeezProperties 바인딩
public class BeezBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeezBackendApplication.class, args);
    }
}
