package com.logslim.cli;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;

@Component
@Command(name = "clear", mixinStandardHelpOptions = true, description = "Delete all data from the database (templates, log entries, raw logs).")
public class ClearCommand implements Runnable {

    private final NamedParameterJdbcTemplate jdbc;

    @Option(names = { "--yes", "-y" }, description = "Skip confirmation prompt")
    private boolean yes;

    public ClearCommand(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run() {
        if (!yes) {
            System.out.print("This will delete all data in the database. Proceed? [y/N] ");
            String input = System.console() != null
                    ? System.console().readLine()
                    : new java.util.Scanner(System.in).nextLine();
            if (input == null || !input.trim().equalsIgnoreCase("y")) {
                System.out.println("Aborted.");
                return;
            }
        }

        int entries = jdbc.update("DELETE FROM log_entries", Map.of());
        int templates = jdbc.update("DELETE FROM templates", Map.of());
        int raw = jdbc.update("DELETE FROM raw_logs", Map.of());

        System.out.printf("Cleared: %d log entries, %d templates, %d raw logs.%n",
                entries, templates, raw);
    }
}
