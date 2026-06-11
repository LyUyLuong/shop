CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX idx_users_email_lower
    ON users (LOWER(email));

CREATE TABLE roles (
    id          UUID PRIMARY KEY,
    name        VARCHAR(30) NOT NULL UNIQUE CHECK (name IN ('USER', 'ADMIN')),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role_id
    ON user_roles (role_id);

INSERT INTO roles (id, name, created_at)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'USER', NOW()),
    ('00000000-0000-0000-0000-000000000102', 'ADMIN', NOW());
