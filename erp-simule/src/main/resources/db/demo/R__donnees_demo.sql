-- ============================================================================
-- Donnees de demonstration (profil Spring "demo" uniquement).
--
-- Migration Flyway REPETABLE (prefixe R__) : rejouee automatiquement quand son
-- contenu change. Elle est donc IDEMPOTENTE : chaque insertion ignore ce qui
-- existe deja (ON CONFLICT sur les cles uniques, NOT EXISTS sinon).
-- Les personnes et societes sont fictives.
-- ============================================================================

-- Un utilisateur par role du workflow Achat Interne
INSERT INTO erp.utilisateur (nom, prenom, email, role) VALUES
    ('Demandeur', 'Mehdi',      'mehdi.demandeur@demo.local', 'DEMANDEUR'),
    ('N1',        'Manager',    'manager.n1@demo.local',      'MANAGER_N1'),
    ('Technique', 'Technicien', 'technicien@demo.local',      'TECHNICIEN'),
    ('Achats',    'Acheteur',   'acheteur@demo.local',        'ACHETEUR'),
    ('Moyens',    'AMG',        'amg@demo.local',             'AMG'),
    ('Finances',  'DAF',        'daf@demo.local',             'DAF'),
    ('Direction', 'DG',         'dg@demo.local',              'DG')
ON CONFLICT (email) DO NOTHING;

-- Fournisseurs (aucune cle unique sur la raison sociale : garde NOT EXISTS)
INSERT INTO erp.fournisseur (raison_sociale, contact)
SELECT v.raison_sociale, v.contact
FROM (VALUES
    ('Fournisseur Demo Informatique', 'contact@fournisseur-informatique.demo.local'),
    ('Fournisseur Demo Bureautique',  'contact@fournisseur-bureautique.demo.local')
) AS v(raison_sociale, contact)
WHERE NOT EXISTS (
    SELECT 1 FROM erp.fournisseur f WHERE f.raison_sociale = v.raison_sociale
);

-- Enveloppe budgetaire : une famille, deux sous-familles (montants en unite monetaire locale)
INSERT INTO erp.famille_budgetaire (code, libelle, montant_alloue)
VALUES ('INFO', 'Informatique', 500000.00)
ON CONFLICT (code) DO NOTHING;

INSERT INTO erp.sous_famille_budgetaire (famille_budgetaire_id, code, libelle, montant_alloue)
SELECT f.id, v.code, v.libelle, v.montant_alloue
FROM erp.famille_budgetaire f
CROSS JOIN (VALUES
    ('INFO-MAT', 'Materiel informatique', 200000.00),
    ('INFO-LOG', 'Logiciels et licences',  100000.00)
) AS v(code, libelle, montant_alloue)
WHERE f.code = 'INFO'
ON CONFLICT (code) DO NOTHING;
