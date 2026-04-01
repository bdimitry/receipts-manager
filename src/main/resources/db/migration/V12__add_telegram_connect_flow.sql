ALTER TABLE users
    ADD COLUMN telegram_connected_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE telegram_connect_tokens
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT                   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      VARCHAR(120)             NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at    TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_telegram_connect_tokens_user_id
    ON telegram_connect_tokens (user_id);

CREATE INDEX idx_telegram_connect_tokens_expires_at
    ON telegram_connect_tokens (expires_at);

CREATE TABLE telegram_polling_state
(
    id             BIGINT PRIMARY KEY,
    last_update_id BIGINT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
