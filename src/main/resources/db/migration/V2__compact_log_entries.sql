-- Replace log_entries with a compact schema:
--   log_timestamp : INTEGER (epoch ms) instead of 29-char TEXT
--   parameter_values : JSON array ["v1","v2"] instead of named-key map
--   continuation_text : plain TEXT column instead of embedded in metadata_json
--   dropped: created_at, metadata_json (source is not stored per-row)
DROP TABLE IF EXISTS log_entries;
CREATE TABLE log_entries (
    entry_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id       INTEGER NOT NULL REFERENCES templates(template_id),
    log_timestamp     INTEGER NOT NULL,
    parameter_values  TEXT    NOT NULL DEFAULT '[]',
    continuation_text TEXT
);
CREATE INDEX IF NOT EXISTS idx_le_template  ON log_entries(template_id);
CREATE INDEX IF NOT EXISTS idx_le_timestamp ON log_entries(log_timestamp);
