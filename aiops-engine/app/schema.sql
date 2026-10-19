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

-- Base de connaissance vectorielle (ADR-012) : un vecteur de 768 nombres par
-- document (trace d'echec, puis regle metier a J6). "modele" garde le nom du
-- modele d'embedding : les vecteurs de deux modeles differents ne sont pas
-- comparables entre eux.
CREATE TABLE IF NOT EXISTS aiops.document_vectorise (
    id            BIGSERIAL PRIMARY KEY,
    type_document VARCHAR(30)  NOT NULL,
    reference     VARCHAR(100) NOT NULL,
    texte         TEXT         NOT NULL,
    modele        VARCHAR(60)  NOT NULL,
    embedding     vector(768)  NOT NULL,
    cree_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Un vecteur par document ET par modele : changer de modele n'efface rien.
    CONSTRAINT uq_document_vectorise UNIQUE (type_document, reference, modele)
);

-- Index HNSW (recherche approximative rapide des plus proches voisins).
-- vector_cosine_ops : la recherche DOIT utiliser l'operateur <=> (distance cosinus).
CREATE INDEX IF NOT EXISTS idx_document_vectorise_hnsw
    ON aiops.document_vectorise USING hnsw (embedding vector_cosine_ops);

-- Historique des diagnostics (ADR-013) : resultat, modele, cout et latence de chaque
-- appel. Sert a l'evaluation (J9) et au tableau de bord.
CREATE TABLE IF NOT EXISTS aiops.diagnostic (
    id                      BIGSERIAL PRIMARY KEY,
    business_correlation_id UUID         NOT NULL,
    cause_racine            TEXT         NOT NULL,
    confiance               NUMERIC(4,3) NOT NULL CHECK (confiance BETWEEN 0 AND 1),
    composant               VARCHAR(20)  NOT NULL,
    preuves                 TEXT[]       NOT NULL,
    preuves_rejetees        INT          NOT NULL DEFAULT 0,
    modele                  VARCHAR(60)  NOT NULL,
    latence_ms              INT          NOT NULL,
    jetons_entree           INT,
    jetons_sortie           INT,
    cree_le                 TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_diagnostic_correlation
    ON aiops.diagnostic (business_correlation_id, cree_le);
