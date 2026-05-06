package com.logslim.cli;

import com.logslim.query.TemplateQueryService;
import com.logslim.query.TemplateQueryService.SlotStats;
import com.logslim.query.TemplateQueryService.TemplateDetailFull;
import com.logslim.reconstruction.LogReconstructor;
import com.logslim.storage.LogEntry;
import com.logslim.storage.Template;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Component
@Command(name = "inspect", mixinStandardHelpOptions = true,
         description = "Inspect a template and its recent log entries.")
public class InspectCommand implements Runnable {

    private static final DateTimeFormatter LOG_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TemplateQueryService queryService;
    private final LogReconstructor reconstructor;

    @Parameters(index = "0", description = "Template ID (numeric)")
    private long templateId;

    @Option(names = {"--recent", "-n"}, description = "Number of recent entries to show (default: 10)",
            defaultValue = "10")
    private int recent;

    public InspectCommand(TemplateQueryService queryService, LogReconstructor reconstructor) {
        this.queryService = queryService;
        this.reconstructor = reconstructor;
    }

    @Override
    public void run() {
        queryService.getTemplateFull(templateId, recent).ifPresentOrElse(
                this::printDetail,
                () -> System.err.println("Template " + templateId + " not found.")
        );
    }

    private void printDetail(TemplateDetailFull detail) {
        Template t = detail.template();

        System.out.printf("Template  #%d ─── %s%n", t.getId(), t.getPattern());
        System.out.println("─".repeat(57));

        String created = TimeFormatter.absoluteDate(t.getCreatedAt())
                + "  (" + TimeFormatter.relativeTime(t.getCreatedAt()) + ")";
        String updated = TimeFormatter.absoluteDate(t.getUpdatedAt())
                + "  (" + TimeFormatter.relativeTime(t.getUpdatedAt()) + ")";
        System.out.printf("Hits:       %-10d Created: %s%n", t.getOccurrences(), created);
        System.out.printf("            %-10s Updated: %s%n", "", updated);

        if (!detail.slotStats().isEmpty()) {
            System.out.println();
            System.out.println("Parameters:");
            for (SlotStats s : detail.slotStats()) {
                String top = s.topValues().stream()
                        .map(e -> e.getKey() + " (" + e.getValue() + "×)")
                        .collect(Collectors.joining(", "));
                String topPart = top.isEmpty() ? "no data" : "top: " + top;
                System.out.printf("  [%d] {%s}   %d distinct ─ %s%n",
                        s.slotIndex(), s.slotType(), s.distinctCount(), topPart);
            }
        }

        System.out.println();
        System.out.printf("Recent logs (%d of %,d):%n",
                detail.recentEntries().size(), t.getOccurrences());
        for (LogEntry e : detail.recentEntries()) {
            String ts = LOG_TS_FMT.format(e.getLogTimestamp());
            String line = reconstructor.reconstruct(t.getPattern(), e.getParameterValues());
            System.out.printf("  %s  %s%n", ts, line);
            if (e.getContinuationText() != null && !e.getContinuationText().isEmpty()) {
                for (String cont : e.getContinuationText().split("\n")) {
                    System.out.println("    " + cont);
                }
            }
        }
    }
}
