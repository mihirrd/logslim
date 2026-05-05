package com.logslim.cli;

import com.logslim.query.TemplateQueryService;
import com.logslim.query.TemplateQueryService.TemplateDetail;
import com.logslim.storage.LogEntry;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "inspect", mixinStandardHelpOptions = true,
         description = "Inspect a template and its recent log entries.")
public class InspectCommand implements Runnable {

    private final TemplateQueryService queryService;

    @Parameters(index = "0", description = "Template ID (numeric)")
    private long templateId;

    @Option(names = {"--recent", "-n"}, description = "Number of recent entries to show (default: 10)",
            defaultValue = "10")
    private int recent;

    public InspectCommand(TemplateQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        queryService.getTemplate(templateId, recent).ifPresentOrElse(
                this::printDetail,
                () -> System.err.println("Template " + templateId + " not found.")
        );
    }

    private void printDetail(TemplateDetail detail) {
        System.out.println("Template: " + detail.template().getPattern());
        System.out.println("ID:       " + detail.template().getId());
        System.out.println("Hits:     " + detail.template().getOccurrences());
        System.out.println();

        if (detail.recentEntries().isEmpty()) {
            System.out.println("No entries.");
            return;
        }
        System.out.println("Recent entries:");
        for (LogEntry e : detail.recentEntries()) {
            System.out.printf("  %s  %s%n", e.getLogTimestamp(), e.getParameterValues());
            if (e.getContinuationText() != null && !e.getContinuationText().isEmpty()) {
                for (String line : e.getContinuationText().split("\n")) {
                    System.out.println("    " + line);
                }
            }
        }
    }
}
