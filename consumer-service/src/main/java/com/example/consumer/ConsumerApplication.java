package com.example.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Consumer service entry point. Listeners and DLX topology are added in Phases 2/3/5.
 */
@SpringBootApplication(excludeName = "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration")
public class ConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
