package com.logslim.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

@Component
public class CliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final CommandLine commandLine;
    private int exitCode;

    public CliRunner(CommandLine commandLine) {
        this.commandLine = commandLine;
    }

    @Override
    public void run(String... args) {
        exitCode = commandLine.execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
