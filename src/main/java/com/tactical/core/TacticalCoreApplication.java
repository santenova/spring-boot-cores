package com.tactical.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TacticalCoreApplication {

    /** Boots the application; the configuration scan binds the tactical properties. */
    public static void main(String[] args) {
        SpringApplication.run(TacticalCoreApplication.class, args);
    }
}
