ALTER TABLE receipts
    ADD COLUMN parsed_currency VARCHAR(3),
    ADD COLUMN normalized_ocr_lines_json TEXT,
    ADD COLUMN parser_ready_text TEXT;
