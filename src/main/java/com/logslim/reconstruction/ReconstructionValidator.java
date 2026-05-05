package com.logslim.reconstruction;

import com.logslim.storage.LogEntry;
import com.logslim.storage.RawLog;
import com.logslim.storage.RawLogDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Validates that a reconstructed log is byte-equivalent to the original.
 * On mismatch: logs the failure and persists the original to raw_logs as a safety net.
 */
@Component
public class ReconstructionValidator {

    private static final Logger log = LoggerFactory.getLogger(ReconstructionValidator.class);

    private final LogReconstructor reconstructor;
    private final RawLogDao rawLogDao;

    public ReconstructionValidator(LogReconstructor reconstructor, RawLogDao rawLogDao) {
        this.reconstructor = reconstructor;
        this.rawLogDao = rawLogDao;
    }

    /**
     * Validate that reconstructing the entry yields the original line.
     * Returns true if valid. On failure, stores the original in raw_logs and returns false.
     */
    public boolean validate(LogEntry entry, String originalLine) {
        try {
            String reconstructed = reconstructor.reconstruct(entry);
            if (originalLine.equals(reconstructed)) {
                return true;
            }
            String origHash  = sha256(originalLine);
            String reconHash = sha256(reconstructed);
            log.error("Reconstruction mismatch for entry_id={}: original_sha256={} reconstructed_sha256={}",
                    entry.getId(), origHash, reconHash);
            storeRawFallback(originalLine, entry);
            return false;
        } catch (ReconstructionException e) {
            log.error("Reconstruction failed for entry_id={}: {}", entry.getId(), e.getMessage());
            storeRawFallback(originalLine, entry);
            return false;
        }
    }

    private void storeRawFallback(String content, LogEntry entry) {
        rawLogDao.insert(new RawLog(
                null, content,
                entry.getLogTimestamp(),
                "reconstruction-fallback",
                Instant.now()
        ));
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
