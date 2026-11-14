CREATE TABLE accounts (
    account_id VARCHAR(12) PRIMARY KEY CHECK (account_id ~ '^[0-9]{12}$'),
    currency CHAR(3) NOT NULL,
    available_balance NUMERIC(18, 2) NOT NULL CHECK (available_balance >= 0),
    daily_outward_limit NUMERIC(18, 2) NOT NULL CHECK (daily_outward_limit >= 0),
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE TABLE wire_payment (
    id BIGSERIAL PRIMARY KEY,
    uetr UUID NOT NULL UNIQUE,
    msg_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    charge_bearer VARCHAR(4) NOT NULL,
    debtor_account_id VARCHAR(12) NOT NULL,
    creditor_account_id VARCHAR(12) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('APPROVED', 'HELD', 'REJECTED')),
    fee NUMERIC(18, 2) NOT NULL,
    reasons JSONB NOT NULL,
    request_payload JSONB NOT NULL,
    response_payload JSONB NOT NULL
);

CREATE INDEX idx_wire_payment_status_created_id
    ON wire_payment (status, created_at DESC, id DESC);

CREATE TABLE funds_earmark (
    id BIGSERIAL PRIMARY KEY,
    uetr UUID NOT NULL UNIQUE REFERENCES wire_payment (uetr),
    account_id VARCHAR(12) NOT NULL REFERENCES accounts (account_id),
    amount NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    fee NUMERIC(18, 2) NOT NULL CHECK (fee >= 0),
    business_date DATE NOT NULL
);

CREATE INDEX idx_funds_earmark_account_date
    ON funds_earmark (account_id, business_date);

CREATE TABLE alert (
    id BIGSERIAL PRIMARY KEY,
    uetr UUID NOT NULL UNIQUE REFERENCES wire_payment (uetr),
    status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
