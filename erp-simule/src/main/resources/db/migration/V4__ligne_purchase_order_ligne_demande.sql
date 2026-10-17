-- ============================================================================
-- V4 : alignement de ligne_purchase_order sur l'entite LignePurchaseOrder
--
-- Constat (ADR-003) : l'entite reference la ligne de demande d'origine
-- (ligne_demande_id), alors que V1 avait cree une reference directe a l'article
-- (article_id NOT NULL), jamais mappee. Consequences avant V4 :
--   - echec du demarrage avec ddl-auto=validate (colonne ligne_demande_id absente) ;
--   - toute generation de PO aurait echoue (article_id NOT NULL non renseigne),
--     et les lignes en texte libre (ligne_demande.article_id NULL) n'auraient
--     jamais pu etre commandees.
--
-- La ligne de PO trace desormais la ligne de demande ; l'article reste
-- accessible via ligne_demande.article_id.
--
-- Hypothese : la table est vide (aucune base deployee avec des PO a ce stade).
-- ============================================================================

ALTER TABLE erp.ligne_purchase_order
    ADD COLUMN ligne_demande_id BIGINT NOT NULL
        REFERENCES erp.ligne_demande(id);

ALTER TABLE erp.ligne_purchase_order
    DROP COLUMN article_id;

CREATE INDEX idx_ligne_po_ligne_demande ON erp.ligne_purchase_order (ligne_demande_id);
