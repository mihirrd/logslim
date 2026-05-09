package com.logslim.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

@Service
public class AdminService {

    private final JdbcTemplate jdbc;

    public AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void compactDatabase(Path dataDir) {
        dataDir.toFile().mkdirs();

        String templatesParquet = dataDir.resolve("templates.parquet").toAbsolutePath().toString();
        String logEntriesParquet = dataDir.resolve("log_entries.parquet").toAbsolutePath().toString();
        String rawLogsParquet = dataDir.resolve("raw_logs.parquet").toAbsolutePath().toString();

        jdbc.execute("COPY templates TO '" + templatesParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        jdbc.execute("COPY log_entries TO '" + logEntriesParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
        jdbc.execute("COPY raw_logs TO '" + rawLogsParquet + "' (FORMAT PARQUET, COMPRESSION ZSTD)");

        for (String name : List.of("log_entries", "raw_logs", "templates")) {
            dropObject(name);
        }
        jdbc.execute("DROP SEQUENCE IF EXISTS templates_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS log_entries_id_seq");
        jdbc.execute("DROP SEQUENCE IF EXISTS raw_logs_id_seq");

        jdbc.execute("CREATE VIEW templates AS SELECT * FROM read_parquet('" + templatesParquet + "')");
        jdbc.execute("CREATE VIEW log_entries AS SELECT * FROM read_parquet('" + logEntriesParquet + "')");
        jdbc.execute("CREATE VIEW raw_logs AS SELECT * FROM read_parquet('" + rawLogsParquet + "')");
        jdbc.execute("CHECKPOINT");
    }

    public long calculateParquetSize(Path dataDir) {
        String templatesParquet = dataDir.resolve("templates.parquet").toAbsolutePath().toString();
        String logEntriesParquet = dataDir.resolve("log_entries.parquet").toAbsolutePath().toString();
        String rawLogsParquet = dataDir.resolve("raw_logs.parquet").toAbsolutePath().toString();

        return new File(templatesParquet).length()
                + new File(logEntriesParquet).length()
                + new File(rawLogsParquet).length();
    }

    private void dropObject(String name) {
        String type = jdbc.queryForObject(
                "SELECT table_type FROM information_schema.tables WHERE table_name = ?",
                String.class, name);
        if (type == null || type.isEmpty())
            return;
        if ("VIEW".equals(type)) {
            jdbc.execute("DROP VIEW " + name);
        } else {
            jdbc.execute("DROP TABLE " + name);
        }
    }
}