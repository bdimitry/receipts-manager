CREATE TABLE purchase_items (
    id BIGSERIAL PRIMARY KEY,
    purchase_id BIGINT NOT NULL REFERENCES purchases (id) ON DELETE CASCADE,
    line_index INTEGER NOT NULL,
    title VARCHAR(255) NOT NULL,
    quantity NUMERIC(19, 3),
    unit VARCHAR(50),
    unit_price NUMERIC(19, 2),
    line_total NUMERIC(19, 2)
);

CREATE INDEX idx_purchase_items_purchase_id ON purchase_items (purchase_id);
CREATE UNIQUE INDEX uk_purchase_items_purchase_line_index ON purchase_items (purchase_id, line_index);
