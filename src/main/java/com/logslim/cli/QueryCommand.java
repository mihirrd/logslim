package com.logslim.cli;

import com.logslim.query.LogQueryService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Duration;
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

    @Option(names = {"--filter"}, description = "Parameter filter key=value (repeatable)",
            mapFallbackValue = "")
    private Map<String, String> filters = new LinkedHashMap<>();

    public QueryCommand(LogQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        List<String> results = queryService.queryByPattern(pattern, filters, last);
        if (results.isEmpty()) {
            System.out.println("No matching logs found.");
            return;
        }
        results.forEach(System.out::println);
        System.out.printf("%n(%d result%s)%n", results.size(), results.size() == 1 ? "" : "s");
    }
}
