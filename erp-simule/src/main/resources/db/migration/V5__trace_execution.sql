-- ============================================================================
-- V5 : trace technique d'execution des services (ADR-010).
--
-- Une ligne par appel de methode publique d'un service metier : succes ou
-- echec, duree, identifiants de correlation. Ecrite dans une transaction
-- SEPAREE de la transaction metier : un rollback metier n'efface pas la trace
-- de l'echec. Distincte de historique_validation (piste d'audit fonctionnelle).
--
-- Transport vers aiops-engine (role aiops_reader, lecture seule) : chaque
-- insertion emet pg_notify('trace_execution', id). La notification ne porte que
-- l'identifiant ; la donnee reste dans la table, relisible si l'ecouteur etait
-- absent (une notification n'est remise qu'aux sessions deja en ecoute).
-- ============================================================================

CREATE TABLE erp.trace_execution (
    id                      BIGSERIAL PRIMARY KEY,
    debut                   TIMESTAMPTZ  NOT NULL,
    duree_ms                BIGINT       NOT NULL,
    request_id              VARCHAR(64),
    business_correlation_id UUID,
    composant               VARCHAR(100) NOT NULL,
    operation               VARCHAR(100) NOT NULL,
    statut                  VARCHAR(10)  NOT NULL,
    exception_type          VARCHAR(255),
    exception_message       VARCHAR(1000),
    CONSTRAINT ck_trace_execution_statut CHECK (statut IN ('SUCCES', 'ECHEC'))
);

CREATE INDEX idx_trace_execution_correlation ON erp.trace_execution (business_correlation_id, debut);
CREATE INDEX idx_trace_execution_request ON erp.trace_execution (request_id);

CREATE FUNCTION erp.notifier_trace_execution() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM pg_notify('trace_execution', NEW.id::text);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_trace_execution_notify
    AFTER INSERT ON erp.trace_execution
    FOR EACH ROW EXECUTE FUNCTION erp.notifier_trace_execution();
