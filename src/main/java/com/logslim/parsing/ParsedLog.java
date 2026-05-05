package com.logslim.parsing;

import java.time.Instant;
import java.util.List;

public record ParsedLog(
        List<Token> tokens,
        String originalContent,
        Instant timestamp,
        String source
) {}
