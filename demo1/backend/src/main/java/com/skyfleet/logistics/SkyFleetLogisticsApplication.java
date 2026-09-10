package com.skyfleet.logistics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SkyFleetLogisticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkyFleetLogisticsApplication.class, args);
    }
}

