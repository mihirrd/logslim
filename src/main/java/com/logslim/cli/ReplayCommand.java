package com.logslim.cli;

import com.logslim.query.LogQueryService;
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
@Command(name = "replay", mixinStandardHelpOptions = true,
         description = "Replay exact original logs from storage in timestamp order.")
public class ReplayCommand implements Runnable {

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LogQueryService queryService;

    @Option(names = {"--last"}, description = "Relative window from now, e.g. 10m, 2h, 1d",
            converter = DurationConverter.class)
    private Duration last;

    @Option(names = {"--from"}, description = "Start of window, e.g. 2024-01-15T14:00:00Z or '2024-01-15 14:00:00'")
    private String from;

    @Option(names = {"--to"}, description = "End of window, e.g. 2024-01-15T15:00:00Z or '2024-01-15 15:00:00'")
    private String to;

    public ReplayCommand(LogQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        List<String> logs;

        if (from != null || to != null) {
            Instant fromInstant = from != null ? parseInstant(from, "--from") : Instant.EPOCH;
            Instant toInstant   = to   != null ? parseInstant(to,   "--to")   : Instant.now();
            logs = queryService.replayLogs(fromInstant, toInstant);
        } else {
            logs = queryService.replayLogs(last);
        }

        if (logs.isEmpty()) {
            System.out.println("No logs found in the specified window.");
            return;
        }
        logs.forEach(System.out::println);
    }

    private static Instant parseInstant(String value, String flag) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {}
        try {
            return LocalDateTime.parse(value, LOCAL_FMT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {}
        System.err.printf("Error: %s value '%s' could not be parsed.%n", flag, value);
        System.err.println("       Use ISO-8601 (e.g. 2024-01-15T14:00:00Z) or 'yyyy-MM-dd HH:mm:ss'.");
        System.exit(1);
        return null;
    }
}
