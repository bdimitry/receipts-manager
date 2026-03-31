CREATE TABLE receipts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    purchase_id BIGINT,
    original_file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    s3_key VARCHAR(1024) NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_receipts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_receipts_purchase FOREIGN KEY (purchase_id) REFERENCES purchases (id) ON DELETE SET NULL,
    CONSTRAINT uk_receipts_s3_key UNIQUE (s3_key)
);

CREATE INDEX idx_receipts_user_id ON receipts (user_id);
CREATE INDEX idx_receipts_purchase_id ON receipts (purchase_id);
CREATE INDEX idx_receipts_user_uploaded_at ON receipts (user_id, uploaded_at);
