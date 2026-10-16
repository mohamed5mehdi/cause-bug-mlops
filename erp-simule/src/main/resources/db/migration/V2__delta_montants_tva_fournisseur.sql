-- V2 : Delta pour la Phase 3 (Montants, Ajustements budgétaires, Numérotation PO)

-- 1. Ajout de colonnes sur demande_achat_interne
ALTER TABLE erp.demande_achat_interne
    ADD COLUMN type_ajustement VARCHAR(50),
    ADD COLUMN montant_ht NUMERIC(15,2),
    ADD COLUMN taux_tva NUMERIC(5,4),
    ADD COLUMN montant_ttc NUMERIC(15,2),
    ADD COLUMN fournisseur_selectionne_id BIGINT REFERENCES erp.fournisseur(id);

-- 1.bis. Ajout de colonnes sur ligne_demande
ALTER TABLE erp.ligne_demande
    ADD COLUMN prix_unitaire_ht NUMERIC(15,2);

-- 2. Création de la table demande_ajustement
CREATE SEQUENCE erp.demande_ajustement_seq START 1;

CREATE TABLE erp.demande_ajustement (
    id BIGINT PRIMARY KEY DEFAULT nextval('erp.demande_ajustement_seq'),
    demande_interne_id UUID REFERENCES erp.demande_achat_interne(id),
    type VARCHAR(50) NOT NULL,
    statut VARCHAR(50) NOT NULL,
    source_sous_famille_id BIGINT,
    cible_sous_famille_id BIGINT,
    famille_cible_id BIGINT,
    montant_demande NUMERIC(15, 2) NOT NULL,
    montant_final NUMERIC(15, 2),
    justification_acheteur VARCHAR(1000) NOT NULL,
    justification_valideur VARCHAR(1000),
    statut_demande_interne_avant_ajustement VARCHAR(50) NOT NULL,
    acheteur_id BIGINT REFERENCES erp.utilisateur(id),
    valideur_id BIGINT REFERENCES erp.utilisateur(id),
    date_creation TIMESTAMP NOT NULL,
    date_decision TIMESTAMP
);

-- 3. Compteur PO en SQL
CREATE SEQUENCE erp.po_seq START 1000;

CREATE OR REPLACE FUNCTION erp.po_numero_compteur() RETURNS text AS $$
BEGIN
  RETURN 'PO-' || to_char(current_date, 'YYYYMMDD') || '-' || lpad(nextval('erp.po_seq')::text, 4, '0');
END;
$$ LANGUAGE plpgsql;
