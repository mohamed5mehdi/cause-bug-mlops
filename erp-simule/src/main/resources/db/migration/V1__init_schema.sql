-- ============================================================================
-- Cause.Bug.MLOps - erp-simule
-- V1 : schema initial du domaine Achat Interne / Achat de Pieces
--
-- Ordre de creation contraint par les dependances FK :
-- utilisateur, famille_budgetaire, sous_famille_budgetaire, article,
-- demande_achat_interne, ligne_demande, historique_validation,
-- ajustement_budgetaire, fournisseur, offre_fournisseur, purchase_order,
-- ligne_purchase_order, goods_receipt_note, goods_receipt_clearing.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS erp;

-- ----------------------------------------------------------------------------
-- Utilisateur
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.utilisateur_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.utilisateur (
    id              BIGINT PRIMARY KEY DEFAULT nextval('erp.utilisateur_seq'),
    nom             VARCHAR(100) NOT NULL,
    prenom          VARCHAR(100) NOT NULL,
    email           VARCHAR(180) NOT NULL,
    role            VARCHAR(30)  NOT NULL,
    actif           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_utilisateur_email UNIQUE (email),
    CONSTRAINT ck_utilisateur_role CHECK (role IN
        ('DEMANDEUR','MANAGER_N1','TECHNICIEN','ACHETEUR','AMG','DAF','DG','MAGASINIER','COMPTABLE'))
);

-- ----------------------------------------------------------------------------
-- Budget (famille_budgetaire.montant_engage est debite via verrou pessimiste
-- FamilleBudgetaireRepository#findByIdForUpdate, sous_famille via
-- SousFamilleBudgetaireRepository#findByIdForUpdate - cf. couche Java)
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.famille_budgetaire_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.famille_budgetaire (
    id              BIGINT PRIMARY KEY DEFAULT nextval('erp.famille_budgetaire_seq'),
    code            VARCHAR(30)  NOT NULL,
    libelle         VARCHAR(150) NOT NULL,
    montant_alloue  NUMERIC(15,2) NOT NULL,
    montant_engage  NUMERIC(15,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_famille_code UNIQUE (code),
    CONSTRAINT ck_famille_montants CHECK (montant_engage >= 0 AND montant_engage <= montant_alloue)
);

CREATE SEQUENCE erp.sous_famille_budgetaire_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.sous_famille_budgetaire (
    id                      BIGINT PRIMARY KEY DEFAULT nextval('erp.sous_famille_budgetaire_seq'),
    famille_budgetaire_id   BIGINT NOT NULL REFERENCES erp.famille_budgetaire(id),
    code                    VARCHAR(30)  NOT NULL,
    libelle                 VARCHAR(150) NOT NULL,
    montant_alloue          NUMERIC(15,2) NOT NULL,
    montant_engage          NUMERIC(15,2) NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_sous_famille_code UNIQUE (code),
    CONSTRAINT ck_sous_famille_montants CHECK (montant_engage >= 0 AND montant_engage <= montant_alloue)
);

CREATE INDEX idx_sous_famille_famille ON erp.sous_famille_budgetaire (famille_budgetaire_id);

-- ----------------------------------------------------------------------------
-- Article (referentiel + stock ; decrementation via verrou pessimiste
-- ArticleRepository#findByIdForUpdate)
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.article_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.article (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('erp.article_seq'),
    reference           VARCHAR(50)  NOT NULL,
    designation         VARCHAR(200) NOT NULL,
    categorie           VARCHAR(100),
    quantite_en_stock   INTEGER NOT NULL DEFAULT 0,
    seuil_alerte        INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_article_reference UNIQUE (reference),
    CONSTRAINT ck_article_stock CHECK (quantite_en_stock >= 0)
);

-- ----------------------------------------------------------------------------
-- Demande d'Achat Interne (entite pivot ; id UUID = Business-Correlation-ID,
-- genere cote application par Hibernate GenerationType.UUID - le DEFAULT
-- gen_random_uuid() ci-dessous est un filet de securite pour toute insertion
-- SQL directe hors couche JPA, ex. jeux de donnees de test)
-- ----------------------------------------------------------------------------
CREATE TABLE erp.demande_achat_interne (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type_demande                    VARCHAR(20) NOT NULL,
    demandeur_id                    BIGINT NOT NULL REFERENCES erp.utilisateur(id),
    urgence                         VARCHAR(20) NOT NULL,
    justification                   VARCHAR(2000),
    sous_famille_budgetaire_id      BIGINT REFERENCES erp.sous_famille_budgetaire(id),
    article_reference_id            BIGINT REFERENCES erp.article(id),
    montant_valorise                NUMERIC(15,2),
    statut                          VARCHAR(40) NOT NULL,
    bypass_validation_hierarchique  BOOLEAN NOT NULL DEFAULT FALSE,
    etape_rejet                     VARCHAR(30),
    motif_rejet                     VARCHAR(1000),
    date_soumission                 TIMESTAMPTZ,
    version                         BIGINT NOT NULL DEFAULT 0,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_type_demande CHECK (type_demande IN ('ACHAT_INTERNE','ACHAT_PIECE'))
);

CREATE INDEX idx_demande_demandeur ON erp.demande_achat_interne (demandeur_id);
CREATE INDEX idx_demande_statut ON erp.demande_achat_interne (statut);

CREATE SEQUENCE erp.ligne_demande_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.ligne_demande (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('erp.ligne_demande_seq'),
    demande_achat_id    UUID NOT NULL REFERENCES erp.demande_achat_interne(id) ON DELETE CASCADE,
    article_id          BIGINT REFERENCES erp.article(id),
    designation_libre   VARCHAR(200),
    specification       VARCHAR(500),
    quantite            INTEGER NOT NULL,
    justification_ligne VARCHAR(500),
    CONSTRAINT ck_ligne_demande_quantite CHECK (quantite > 0)
);

CREATE INDEX idx_ligne_demande_demande ON erp.ligne_demande (demande_achat_id);

-- ----------------------------------------------------------------------------
-- Historique de validation (piste d'audit FONCTIONNELLE, append-only ; distincte
-- de la table technique transaction_trace_event introduite en Phase 1, non
-- creee par cette migration car proprietaire du service aiops-engine)
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.historique_validation_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.historique_validation (
    id                      BIGINT PRIMARY KEY DEFAULT nextval('erp.historique_validation_seq'),
    demande_achat_id        UUID NOT NULL REFERENCES erp.demande_achat_interne(id),
    etape                   VARCHAR(30) NOT NULL,
    acteur_id               BIGINT NOT NULL REFERENCES erp.utilisateur(id),
    decision                VARCHAR(30) NOT NULL,
    commentaire             VARCHAR(1000),
    business_correlation_id UUID NOT NULL,
    horodatage              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_historique_demande ON erp.historique_validation (demande_achat_id);
CREATE INDEX idx_historique_correlation ON erp.historique_validation (business_correlation_id);

-- ----------------------------------------------------------------------------
-- Ajustement budgetaire
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.ajustement_budgetaire_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.ajustement_budgetaire (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('erp.ajustement_budgetaire_seq'),
    demande_achat_id    UUID NOT NULL REFERENCES erp.demande_achat_interne(id),
    type_ajustement     VARCHAR(20) NOT NULL,
    montant_ajustement  NUMERIC(15,2) NOT NULL,
    valide_par_id       BIGINT NOT NULL REFERENCES erp.utilisateur(id),
    horodatage          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_type_ajustement CHECK (type_ajustement IN ('SOUS_FAMILLE','FAMILLE'))
);

CREATE INDEX idx_ajustement_demande ON erp.ajustement_budgetaire (demande_achat_id);

-- ----------------------------------------------------------------------------
-- Fournisseur / BPU
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.fournisseur_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.fournisseur (
    id              BIGINT PRIMARY KEY DEFAULT nextval('erp.fournisseur_seq'),
    raison_sociale  VARCHAR(200) NOT NULL,
    contact         VARCHAR(200),
    actif           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE SEQUENCE erp.offre_fournisseur_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.offre_fournisseur (
    id              BIGINT PRIMARY KEY DEFAULT nextval('erp.offre_fournisseur_seq'),
    fournisseur_id  BIGINT NOT NULL REFERENCES erp.fournisseur(id),
    article_id      BIGINT NOT NULL REFERENCES erp.article(id),
    prix_unitaire   NUMERIC(15,2) NOT NULL,
    date_validite   DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_offre_fournisseur_article ON erp.offre_fournisseur (article_id);

-- ----------------------------------------------------------------------------
-- Purchase Order
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.purchase_order_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.purchase_order (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('erp.purchase_order_seq'),
    demande_achat_id    UUID NOT NULL UNIQUE REFERENCES erp.demande_achat_interne(id),
    fournisseur_id      BIGINT NOT NULL REFERENCES erp.fournisseur(id),
    numero_po           VARCHAR(40) NOT NULL,
    statut              VARCHAR(20) NOT NULL,
    montant_total       NUMERIC(15,2) NOT NULL,
    date_generation     TIMESTAMPTZ NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_po_numero UNIQUE (numero_po),
    CONSTRAINT ck_po_statut CHECK (statut IN ('DRAFT','APPROVED','POSTED'))
);

CREATE SEQUENCE erp.ligne_purchase_order_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.ligne_purchase_order (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('erp.ligne_purchase_order_seq'),
    purchase_order_id   BIGINT NOT NULL REFERENCES erp.purchase_order(id) ON DELETE CASCADE,
    article_id          BIGINT NOT NULL REFERENCES erp.article(id),
    quantite            INTEGER NOT NULL,
    prix_unitaire       NUMERIC(15,2) NOT NULL,
    montant_ligne       NUMERIC(15,2) NOT NULL,
    CONSTRAINT ck_ligne_po_quantite CHECK (quantite > 0)
);

CREATE INDEX idx_ligne_po_po ON erp.ligne_purchase_order (purchase_order_id);

-- ----------------------------------------------------------------------------
-- Reception physique et rapprochement comptable
-- goods_receipt_clearing.goods_receipt_note_id est NOT NULL + UNIQUE : impose la
-- sequence documentaire GRN -> GRC au niveau du schema (cf. Javadoc GoodsReceiptClearing)
-- ----------------------------------------------------------------------------
CREATE SEQUENCE erp.goods_receipt_note_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.goods_receipt_note (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('erp.goods_receipt_note_seq'),
    purchase_order_id   BIGINT NOT NULL REFERENCES erp.purchase_order(id),
    magasinier_id       BIGINT NOT NULL REFERENCES erp.utilisateur(id),
    quantite_recue      INTEGER NOT NULL,
    horodatage          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_grn_quantite CHECK (quantite_recue > 0)
);

CREATE INDEX idx_grn_po ON erp.goods_receipt_note (purchase_order_id);

CREATE SEQUENCE erp.goods_receipt_clearing_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE erp.goods_receipt_clearing (
    id                      BIGINT PRIMARY KEY DEFAULT nextval('erp.goods_receipt_clearing_seq'),
    goods_receipt_note_id   BIGINT NOT NULL UNIQUE REFERENCES erp.goods_receipt_note(id),
    comptable_id            BIGINT NOT NULL REFERENCES erp.utilisateur(id),
    montant_facture         NUMERIC(15,2) NOT NULL,
    statut                  VARCHAR(20) NOT NULL,
    horodatage              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_grc_statut CHECK (statut IN ('EN_ATTENTE','POSTED'))
);
