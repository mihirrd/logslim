package com.logslim.cli;

import com.logslim.query.LogQueryService;
import com.logslim.storage.Template;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Command(name = "query", mixinStandardHelpOptions = true,
         description = "Query reconstructed logs matching a pattern with optional parameter filters.",
         usageHelpAutoWidth = true)
public class QueryCommand implements Runnable {

    private final LogQueryService queryService;

    @Parameters(index = "0", description = "Template pattern, e.g. \"User {id} failed login\"")
    private String pattern;

    @Option(names = {"--last"}, description = "Time window, e.g. 10m, 2h, 1d",
            converter = DurationConverter.class)
    private Duration last;

    @Option(names = {"--from"}, description = "Start of window, e.g. 2024-01-15T14:00:00Z or '2024-01-15 14:00:00'")
    private String from;

    @Option(names = {"--to"}, description = "End of window, e.g. 2024-01-15T15:00:00Z or '2024-01-15 15:00:00'")
    private String to;

    @Option(names = {"--filter"}, description = "Parameter filter key=value (repeatable)",
            mapFallbackValue = "")
    private Map<String, String> filters = new LinkedHashMap<>();

    public QueryCommand(LogQueryService queryService) {
        this.queryService = queryService;
    }

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void run() {
        List<String> results;
        if (from != null || to != null) {
            Instant fromInstant = from != null ? parseInstant(from, "--from") : Instant.EPOCH;
            Instant toInstant   = to   != null ? parseInstant(to,   "--to")   : Instant.now();
            results = queryService.queryByPattern(pattern, filters, fromInstant, toInstant);
        } else {
            results = queryService.queryByPattern(pattern, filters, last);
        }
        if (results.isEmpty()) {
            System.out.printf("No template matched \"%s\".%n%n", pattern);
            List<Template> suggestions = queryService.findSuggestions(pattern, 5);
            if (!suggestions.isEmpty()) {
                System.out.println("Did you mean?");
                for (Template s : suggestions) {
                    System.out.printf("  [%-4d]  %-40s  (%,d hits)%n",
                            s.getId(), s.getPattern(), s.getOccurrences());
                }
            }
            return;
        }
        results.forEach(System.out::println);
        System.out.printf("%n(%d result%s)%n", results.size(), results.size() == 1 ? "" : "s");
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
