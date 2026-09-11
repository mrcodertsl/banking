CREATE TABLE client (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100),
    phone_number    VARCHAR(20),
    balance         NUMERIC(19,2) NOT NULL DEFAULT 0,
    CONSTRAINT balance_non_negative CHECK ( balance >= 0 )
);