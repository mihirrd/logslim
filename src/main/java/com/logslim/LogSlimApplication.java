package com.logslim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootApplication
@EnableCaching
public class LogSlimApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "serve".equals(args[0])) {
            String dbPath = System.getProperty("logslim.db.path", "logs.duckdb");
            // Server reads from the Parquet snapshot in `<dbname>_data/`, never
            // from logs.duckdb itself — see ParquetDataSourceConfig. The check
            // here surfaces a clear error before Spring tries to wire the bean.
            String dataDirBase = dbPath.endsWith(".duckdb")
                    ? dbPath.substring(0, dbPath.length() - ".duckdb".length())
                    : dbPath;
            if (!Files.exists(Paths.get(dataDirBase + "_data", "templates.parquet"))) {
                System.err.println("ERROR: No Parquet snapshot found at " + dataDirBase + "_data/.");
                System.err.println("       Run `logslim compact -y` first; the dashboard reads only the");
                System.err.println("       compacted snapshot, not the live database.");
                System.exit(1);
            }
            System.setProperty("spring.main.web-application-type", "servlet");
            System.setProperty("spring.sql.init.mode", "never");
            SpringApplication.run(LogSlimApplication.class, args);
        } else {
            System.exit(SpringApplication.exit(
                SpringApplication.run(LogSlimApplication.class, args)));
        }
    }

}
