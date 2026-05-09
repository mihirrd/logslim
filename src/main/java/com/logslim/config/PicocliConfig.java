package com.logslim.config;

import com.logslim.cli.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@Configuration
public class PicocliConfig {

    @Bean
    public CommandLine commandLine(IFactory factory,
            RunCommand run,
            TemplatesCommand templates,
            InspectCommand inspect,
            QueryCommand query,
            ReplayCommand replay,
            CompactCommand compact,
            ServeCommand serve) {
        CommandLine cmd = new CommandLine(new LogSlimCommand(), factory);
        cmd.addSubcommand("run", run);
        cmd.addSubcommand("templates", templates);
        cmd.addSubcommand("inspect", inspect);
        cmd.addSubcommand("query", query);
        cmd.addSubcommand("replay", replay);
        cmd.addSubcommand("compact", compact);
        cmd.addSubcommand("serve", serve);
        return cmd;
    }

    @CommandLine.Command(name = "logslim", mixinStandardHelpOptions = true, version = "0.1.0", description = "Lossless log deduplication engine", subcommands = CommandLine.HelpCommand.class)
    static class LogSlimCommand implements Runnable {
        @Override
        public void run() {
            // No-op: picocli prints usage if no subcommand given
        }
    }
}
