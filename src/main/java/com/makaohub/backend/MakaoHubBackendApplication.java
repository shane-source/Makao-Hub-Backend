package com.makaohub.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MakaoHubBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                MakaoHubBackendApplication.class,
                args
        );
    }
}
