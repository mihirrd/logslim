package com.logslim.api;

import com.logslim.service.AdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminController {

    private final AdminService adminService;

    @Value("${logslim.db.path:logs.duckdb}")
    private String dbPath;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/compact")
    public Map<String, Object> compact() {
        Path dataDir = resolveDataDir();
        adminService.compactDatabase(dataDir);
        return Map.of("message", "Compaction complete. Data exported to " + dataDir);
    }

    private Path resolveDataDir() {
        String base = dbPath.endsWith(".duckdb") ? dbPath.substring(0, dbPath.length() - 7) : dbPath;
        return Paths.get(base + "_data").toAbsolutePath();
    }
}
