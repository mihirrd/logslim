package com.logslim.cli;

import com.logslim.query.LogQueryService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.List;

@Component
@Command(name = "replay", mixinStandardHelpOptions = true,
         description = "Replay exact original logs from storage in timestamp order.")
public class ReplayCommand implements Runnable {

    private final LogQueryService queryService;

    @Option(names = {"--last"}, description = "Time window, e.g. 10m, 2h, 1d",
            converter = DurationConverter.class)
    private Duration last;

    public ReplayCommand(LogQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public void run() {
        List<String> logs = queryService.replayLogs(last);
        if (logs.isEmpty()) {
            System.out.println("No logs found in the specified window.");
            return;
        }
        logs.forEach(System.out::println);
    }
}
