CREATE TABLE IF NOT EXISTS instructions (
 instruction_id VARCHAR(80) PRIMARY KEY, tenant VARCHAR(80) NOT NULL,
 action_key VARCHAR(255) NOT NULL, request_json CLOB NOT NULL,
 status VARCHAR(24) NOT NULL, accepted_at BIGINT NOT NULL,
 UNIQUE(tenant, action_key)
);
CREATE TABLE IF NOT EXISTS gateway_operations (
 tenant VARCHAR(80) NOT NULL, action_key VARCHAR(255) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 instruction_id VARCHAR(80) NOT NULL REFERENCES instructions(instruction_id),
 receipt_json CLOB NOT NULL,
 PRIMARY KEY(tenant, action_key)
);
