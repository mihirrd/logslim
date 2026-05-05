CREATE SEQUENCE IF NOT EXISTS templates_id_seq START 1;
CREATE TABLE IF NOT EXISTS templates (
    template_id BIGINT  DEFAULT nextval('templates_id_seq') PRIMARY KEY,
    pattern     TEXT    NOT NULL UNIQUE,
    occurrences BIGINT  NOT NULL DEFAULT 1,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS raw_logs_id_seq START 1;
CREATE TABLE IF NOT EXISTS raw_logs (
    log_id        BIGINT DEFAULT nextval('raw_logs_id_seq') PRIMARY KEY,
    content       TEXT   NOT NULL,
    log_timestamp TEXT   NOT NULL,
    source        TEXT,
    created_at    TEXT   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_raw_timestamp ON raw_logs(log_timestamp);

CREATE SEQUENCE IF NOT EXISTS log_entries_id_seq START 1;
CREATE TABLE IF NOT EXISTS log_entries (
    entry_id          BIGINT DEFAULT nextval('log_entries_id_seq') PRIMARY KEY,
    template_id       BIGINT NOT NULL REFERENCES templates(template_id),
    log_timestamp     BIGINT NOT NULL,
    parameter_values  TEXT   NOT NULL DEFAULT '[]',
    continuation_text TEXT
);
CREATE INDEX IF NOT EXISTS idx_le_template  ON log_entries(template_id);
CREATE INDEX IF NOT EXISTS idx_le_timestamp ON log_entries(log_timestamp);
