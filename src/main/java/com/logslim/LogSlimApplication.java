package com.logslim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class LogSlimApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "serve".equals(args[0])) {
            System.setProperty("spring.main.web-application-type", "servlet");
            try {
                SpringApplication.run(LogSlimApplication.class, args);
            } catch (Exception e) {
                if (hasLockError(e)) {
                    System.err.println();
                    System.err.println("ERROR: Another process already has the database locked.");
                    System.err.println("       Stop any running logslim processes and try again.");
                    System.err.println("       Run: pkill -f logslim");
                    System.exit(1);
                }
                throw e;
            }
        } else {
            System.exit(SpringApplication.exit(
                SpringApplication.run(LogSlimApplication.class, args)));
        }
    }

    private static boolean hasLockError(Throwable t) {
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains("Could not set lock")) return true;
            t = t.getCause();
        }
        return false;
    }
}
