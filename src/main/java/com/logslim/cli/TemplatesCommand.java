package com.logslim.cli;

import com.logslim.query.TemplateQueryService;
import com.logslim.storage.Template;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.List;

@Component
@Command(name = "templates", mixinStandardHelpOptions = true,
         description = "List top log templates by occurrence count.")
public class TemplatesCommand implements Runnable {

    private final TemplateQueryService queryService;

    @Option(names = {"--last"}, description = "Time window, e.g. 10m, 2h, 1d",
            converter = DurationConverter.class)
    private Duration last;

    @Option(names = {"--limit", "-n"}, description = "Max results (default: 20)", defaultValue = "20")
    private int limit;

    @Option(names = {"--search", "-s"}, description = "Filter templates whose pattern contains this text")
    private String search;

    public TemplatesCommand(TemplateQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        List<Template> templates = (search != null && !search.isBlank())
                ? queryService.searchTemplates(search, limit)
                : queryService.listTopTemplates(last, limit);

        if (templates.isEmpty()) {
            System.out.println("No templates found.");
            return;
        }

        System.out.printf("  %-6s  %-6s  %-13s  %s%n", "ID", "HITS", "LAST SEEN", "PATTERN");
        System.out.println("  " + "─".repeat(70));
        for (Template t : templates) {
            System.out.printf("  [%-4d]  %-6d  %-13s  %s%n",
                    t.getId(), t.getOccurrences(),
                    TimeFormatter.relativeTime(t.getUpdatedAt()), t.getPattern());
        }
    }
}
