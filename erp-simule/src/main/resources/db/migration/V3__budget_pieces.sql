-- V3 : Table et sequence pour le module BudgetPieces (flux ACHAT_PIECE)

CREATE SEQUENCE erp.budget_pieces_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.budget_pieces (
    id BIGINT PRIMARY KEY DEFAULT nextval('erp.budget_pieces_seq'),
    exercice VARCHAR(4) NOT NULL UNIQUE,
    budget_initial NUMERIC(15, 2) NOT NULL,
    budget_engage NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    budget_restant NUMERIC(15, 2) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_budget_pieces_exercice ON erp.budget_pieces (exercice);
