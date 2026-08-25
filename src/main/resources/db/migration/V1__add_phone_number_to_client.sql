ALTER TABLE client ADD COLUMN phone_number VARCHAR(255);

UPDATE client SET phone_number = '+00000000000' WHERE phone_number IS NULL;