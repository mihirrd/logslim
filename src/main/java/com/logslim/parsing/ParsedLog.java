package com.logslim.parsing;

import java.time.Instant;
import java.util.List;

/**
 * Result of tokenizing one log line.
 *
 * @param timestamp          event time parsed from the line, or {@code null} if none could be
 *                           parsed — callers must supply a fallback (e.g. source timestamp or
 *                           carry-forward) rather than treating a missing timestamp as "now".
 * @param timestampResolved  {@code true} iff {@code timestamp} was parsed from the line content.
 */
public record ParsedLog(
        List<Token> tokens,
        String originalContent,
        Instant timestamp,
        String source,
        boolean timestampResolved
) {}
