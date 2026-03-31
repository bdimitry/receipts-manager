CREATE TABLE purchases (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    purchase_date DATE NOT NULL,
    store_name VARCHAR(255),
    comment TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_purchases_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_purchases_user_id ON purchases (user_id);
CREATE INDEX idx_purchases_user_purchase_date ON purchases (user_id, purchase_date);
CREATE INDEX idx_purchases_user_category ON purchases (user_id, category);
