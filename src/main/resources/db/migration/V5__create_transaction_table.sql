CREATE TABLE transaction
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type       VARCHAR(20)    NOT NULL,
    from_id    BIGINT         NOT NULL,
    to_id      BIGINT         NOT NULL,
    amount     NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fk_transaction_from FOREIGN KEY (from_id) REFERENCES client (id),
    CONSTRAINT fk_transaction_to FOREIGN KEY (to_id) REFERENCES client (id),
    CONSTRAINT amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_transaction_from_id ON transaction (from_id);

CREATE INDEX idx_transaction_to_id ON transaction (to_id);