package com.logslim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class LogSlimApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(LogSlimApplication.class, args)));
    }
}
