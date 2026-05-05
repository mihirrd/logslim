CREATE TABLE IF NOT EXISTS templates (
    template_id INTEGER PRIMARY KEY AUTOINCREMENT,
    pattern     TEXT    NOT NULL UNIQUE,
    occurrences INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS log_entries (
    entry_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id     INTEGER NOT NULL REFERENCES templates(template_id),
    log_timestamp   TEXT    NOT NULL,
    parameter_json  TEXT    NOT NULL,
    metadata_json   TEXT,
    created_at      TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_le_template   ON log_entries(template_id);
CREATE INDEX IF NOT EXISTS idx_le_timestamp  ON log_entries(log_timestamp);

CREATE TABLE IF NOT EXISTS raw_logs (
    log_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    content       TEXT NOT NULL,
    log_timestamp TEXT NOT NULL,
    source        TEXT,
    created_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_raw_timestamp ON raw_logs(log_timestamp);
