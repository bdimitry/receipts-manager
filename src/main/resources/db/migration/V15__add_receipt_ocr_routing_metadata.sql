ALTER TABLE receipts
ADD COLUMN receipt_country_hint VARCHAR(30),
ADD COLUMN language_detection_source VARCHAR(30),
ADD COLUMN ocr_profile_strategy VARCHAR(50),
ADD COLUMN ocr_profile_used VARCHAR(50);
