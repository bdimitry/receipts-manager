ALTER TABLE purchases
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'UAH';

ALTER TABLE receipts
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'UAH';

CREATE TABLE receipt_line_items (
    id BIGSERIAL PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    line_index INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    quantity NUMERIC(19, 3),
    unit VARCHAR(30),
    unit_price NUMERIC(19, 2),
    line_total NUMERIC(19, 2),
    raw_fragment TEXT,
    CONSTRAINT fk_receipt_line_items_receipt
        FOREIGN KEY (receipt_id) REFERENCES receipts (id) ON DELETE CASCADE
);

CREATE INDEX idx_receipt_line_items_receipt_id ON receipt_line_items (receipt_id);
CREATE INDEX idx_receipt_line_items_receipt_line_index ON receipt_line_items (receipt_id, line_index);
