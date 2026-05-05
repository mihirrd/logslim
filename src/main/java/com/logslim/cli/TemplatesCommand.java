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

    public TemplatesCommand(TemplateQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        List<Template> templates = queryService.listTopTemplates(last, limit);
        if (templates.isEmpty()) {
            System.out.println("No templates found.");
            return;
        }

        System.out.printf("%-6s  %-10s  %s%n", "ID", "OCCURRENCES", "PATTERN");
        System.out.println("-".repeat(72));
        for (Template t : templates) {
            System.out.printf("[%-4d]  %-10d  %s%n",
                    t.getId(), t.getOccurrences(), t.getPattern());
        }
    }
}
