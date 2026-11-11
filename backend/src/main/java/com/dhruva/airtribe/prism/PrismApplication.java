package com.dhruva.airtribe.prism;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PrismApplication {
    public static void main(String[] args) { SpringApplication.run(PrismApplication.class, args); }
}
