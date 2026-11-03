CREATE TABLE users (
                       id UUID PRIMARY KEY,
                       email VARCHAR(255) NOT NULL UNIQUE,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE login_codes (
                             id UUID PRIMARY KEY,
                             email VARCHAR(255) NOT NULL,
                             code_hash VARCHAR(64) NOT NULL,
                             expires_at TIMESTAMPTZ NOT NULL,
                             attempts INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_login_codes_email ON login_codes(email);

CREATE TABLE initiatives (
                             id UUID PRIMARY KEY,
                             author_id UUID NOT NULL REFERENCES users(id),
                             title VARCHAR(120) NOT NULL,
                             description TEXT NOT NULL,
                             vote_count INT NOT NULL DEFAULT 0,
                             created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_initiatives_popularity ON initiatives(vote_count DESC, created_at DESC);

-- Намеренно нет user_id и времени: голоса анонимны
CREATE TABLE votes (
                       initiative_id UUID NOT NULL REFERENCES initiatives(id) ON DELETE CASCADE,
                       voter_hash BYTEA NOT NULL,
                       PRIMARY KEY (initiative_id, voter_hash)
);