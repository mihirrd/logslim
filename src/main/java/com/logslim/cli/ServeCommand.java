package com.logslim.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(name = "serve", mixinStandardHelpOptions = true,
         description = "Start LogSlim as an HTTP API server on port 8080.")
public class ServeCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("LogSlim API server running on http://localhost:8080");
        System.out.println("Press Ctrl-C to stop.");
    }
}
