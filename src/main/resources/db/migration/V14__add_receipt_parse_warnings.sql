ALTER TABLE receipts
ADD COLUMN parse_warnings_json TEXT,
ADD COLUMN weak_parse_quality BOOLEAN NOT NULL DEFAULT FALSE;
