package com.logslim.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logslim.query.FrequencyDelta;
import com.logslim.query.TemplateQueryService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Command(name = "timeline", mixinStandardHelpOptions = true,
         description = "Analyze template frequency changes for root cause analysis.",
         usageHelpAutoWidth = true)
public class TimelineCommand implements Runnable {

    private final TemplateQueryService queryService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = {"--observed"}, required = true,
            description = "Observed period, e.g. 1h, 6h, 24h",
            converter = DurationConverter.class)
    private Duration observed;

    @Option(names = {"--baseline"}, required = true,
            description = "Baseline period, e.g. 1h, 6h, 24h",
            converter = DurationConverter.class)
    private Duration baseline;

    @Option(names = {"--limit"}, defaultValue = "50",
            description = "Maximum number of results (default: 50)")
    private int limit;

    @Option(names = {"--json"}, defaultValue = "false",
            description = "Output as JSON instead of human-readable format")
    private boolean json;

    public TimelineCommand(TemplateQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        try {
            List<FrequencyDelta> deltas = queryService.compareFrequencies(observed, baseline, limit);

            if (json) {
                outputJson(deltas);
            } else {
                outputHumanReadable(deltas);
            }
        } catch (Exception e) {
            System.err.printf("Error: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    private void outputHumanReadable(List<FrequencyDelta> deltas) {
        if (deltas.isEmpty()) {
            System.out.println("No frequency changes detected.");
            return;
        }

        System.out.printf("%-8s %-50s %-12s %-12s %s%n",
                "Change", "Pattern", "Observed", "Baseline", "");
        System.out.println("-".repeat(95));

        for (FrequencyDelta delta : deltas) {
            String changeStr;
            if (delta.isNew()) {
                changeStr = "[ NEW ]";
            } else if (delta.disappeared()) {
                changeStr = "[GONE ]";
            } else {
                double changePercent = delta.changePercent();
                String sign = changePercent >= 0 ? "+" : "";
                changeStr = String.format("[%s%6.1f%%]", sign, changePercent);
            }

            String pattern = delta.template().getPattern();
            if (pattern.length() > 50) {
                pattern = pattern.substring(0, 47) + "...";
            }

            System.out.printf("%-8s %-50s %12d %12d%n",
                    changeStr,
                    pattern,
                    delta.observedCount(),
                    delta.baselineCount());
        }

        System.out.printf("%n(%d result%s)%n", deltas.size(), deltas.size() == 1 ? "" : "s");
    }

    private void outputJson(List<FrequencyDelta> deltas) {
        try {
            List<Map<String, Object>> results = deltas.stream()
                    .map(this::deltaToMap)
                    .toList();
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results);
            System.out.println(json);
        } catch (Exception e) {
            System.err.printf("Error serializing to JSON: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    private Map<String, Object> deltaToMap(FrequencyDelta delta) {
        return Map.of(
            "id", delta.template().getId(),
            "pattern", delta.template().getPattern(),
            "observedCount", delta.observedCount(),
            "baselineCount", delta.baselineCount(),
            "changePercent", delta.changePercent(),
            "isNew", delta.isNew(),
            "disappeared", delta.disappeared(),
            "createdAt", delta.template().getCreatedAt().toEpochMilli(),
            "updatedAt", delta.template().getUpdatedAt().toEpochMilli()
        );
    }
}
