-- Tables d'aiops-engine dans son schema "aiops" (ADR-011).
-- Script idempotent, rejoue a chaque demarrage. Le schema "aiops" et
-- l'extension pgvector sont crees par infra/postgres/init/02-aiops.sql.

-- Copie des traces d'execution emises par le simulateur d'achats
-- (erp.trace_execution), propriete d'aiops-engine. source_id = id d'origine :
-- la contrainte UNIQUE rend chaque copie idempotente.
CREATE TABLE IF NOT EXISTS aiops.transaction_trace_event (
    id                      BIGSERIAL PRIMARY KEY,
    source_id               BIGINT       NOT NULL UNIQUE,
    debut                   TIMESTAMPTZ  NOT NULL,
    duree_ms                BIGINT       NOT NULL,
    request_id              VARCHAR(64),
    business_correlation_id UUID,
    composant               VARCHAR(100) NOT NULL,
    operation               VARCHAR(100) NOT NULL,
    statut                  VARCHAR(10)  NOT NULL,
    exception_type          VARCHAR(255),
    exception_message       VARCHAR(1000),
    ingere_le               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tte_correlation
    ON aiops.transaction_trace_event (business_correlation_id, debut);
