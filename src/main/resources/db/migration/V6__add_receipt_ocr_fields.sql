ALTER TABLE receipts
    ADD COLUMN ocr_status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    ADD COLUMN raw_ocr_text TEXT,
    ADD COLUMN parsed_store_name VARCHAR(255),
    ADD COLUMN parsed_total_amount NUMERIC(19, 2),
    ADD COLUMN parsed_purchase_date DATE,
    ADD COLUMN ocr_error_message TEXT,
    ADD COLUMN ocr_processed_at TIMESTAMPTZ;
