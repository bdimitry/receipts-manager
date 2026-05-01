ALTER TABLE receipts
    ADD COLUMN ocr_confidence_json TEXT,
    ADD COLUMN ocr_processing_decision VARCHAR(30),
    ADD COLUMN review_status VARCHAR(30) NOT NULL DEFAULT 'UNREVIEWED',
    ADD COLUMN reviewed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN reviewed_by_user_id BIGINT,
    ADD CONSTRAINT fk_receipts_reviewed_by_user FOREIGN KEY (reviewed_by_user_id) REFERENCES users (id) ON DELETE SET NULL;

CREATE TABLE receipt_corrections (
    id BIGSERIAL PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    review_status VARCHAR(30) NOT NULL,
    parsed_snapshot_json TEXT NOT NULL,
    corrected_snapshot_json TEXT NOT NULL,
    correction_diff_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_receipt_corrections_receipt FOREIGN KEY (receipt_id) REFERENCES receipts (id) ON DELETE CASCADE,
    CONSTRAINT fk_receipt_corrections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_receipt_corrections_receipt_created_at ON receipt_corrections (receipt_id, created_at DESC, id DESC);
