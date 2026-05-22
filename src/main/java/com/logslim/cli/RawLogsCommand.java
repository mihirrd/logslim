package com.logslim.cli;

import com.logslim.storage.RawLogDao;
import com.logslim.storage.RawLog;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
@Command(name = "raw-logs", mixinStandardHelpOptions = true,
         description = "Show raw (unmatched or novel) log lines from storage.")
public class RawLogsCommand implements Runnable {

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RawLogDao rawLogDao;

    @Option(names = {"--last"}, description = "Relative window from now, e.g. 10m, 2h, 1d",
            converter = DurationConverter.class)
    private Duration last;

    @Option(names = {"--from"}, description = "Start of window, e.g. 2024-01-15T14:00:00Z")
    private String from;

    @Option(names = {"--to"}, description = "End of window, e.g. 2024-01-15T15:00:00Z")
    private String to;

    @Option(names = {"--search", "-s"}, description = "Filter lines containing this keyword")
    private String search;

    @Option(names = {"--limit", "-n"}, description = "Max lines to return (default 500)",
            defaultValue = "500")
    private int limit;

    public RawLogsCommand(RawLogDao rawLogDao) {
        this.rawLogDao = rawLogDao;
    }

    @Override
    public void run() {
        Instant f, t;
        if (from != null || to != null) {
            f = from != null ? parseInstant(from, "--from") : Instant.EPOCH;
            t = to   != null ? parseInstant(to,   "--to")   : Instant.now();
        } else {
            Duration window = last != null ? last : Duration.ofHours(1);
            t = Instant.now();
            f = t.minus(window);
        }

        List<RawLog> logs = rawLogDao.findByTimeRangeAndSearch(f, t, search, limit);

        if (logs.isEmpty()) {
            System.out.println("No raw logs found in the specified window.");
            return;
        }
        logs.forEach(r -> System.out.println(r.getContent()));
    }

    private static Instant parseInstant(String value, String flag) {
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(value, LOCAL_FMT).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) {}
        System.err.printf("Error: %s value '%s' could not be parsed.%n", flag, value);
        System.err.println("       Use ISO-8601 (e.g. 2024-01-15T14:00:00Z) or 'yyyy-MM-dd HH:mm:ss'.");
        System.exit(1);
        return null;
    }
}
