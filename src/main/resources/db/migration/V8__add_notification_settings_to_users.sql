ALTER TABLE users
    ADD COLUMN telegram_chat_id VARCHAR(255),
    ADD COLUMN preferred_notification_channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL';
