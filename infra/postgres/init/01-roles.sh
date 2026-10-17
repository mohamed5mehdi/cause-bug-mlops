#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# Initialisation des roles applicatifs (ADR-004).
# Execute UNE SEULE FOIS par l'image PostgreSQL, a la creation du volume.
#
#   erp_app      : proprietaire du schema erp (Flyway + application erp-simule).
#   aiops_reader : lecture seule sur erp (aiops-engine) ; aucun droit d'ecriture.
#
# Les mots de passe viennent de l'environnement (.env, jamais versionne) et sont
# transmis a psql comme variables (:'var') : psql les cite lui-meme, sans
# concatenation de chaines dans le shell.
# ----------------------------------------------------------------------------
set -euo pipefail

: "${ERP_APP_PASSWORD:?ERP_APP_PASSWORD manquant (voir .env.example)}"
: "${AIOPS_READER_PASSWORD:?AIOPS_READER_PASSWORD manquant (voir .env.example)}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v erp_pwd="$ERP_APP_PASSWORD" \
     -v aiops_pwd="$AIOPS_READER_PASSWORD" <<'SQL'
CREATE ROLE erp_app      LOGIN PASSWORD :'erp_pwd';
CREATE ROLE aiops_reader LOGIN PASSWORD :'aiops_pwd';

-- Le schema erp appartient a erp_app : Flyway y cree tables, sequences, fonctions.
CREATE SCHEMA erp AUTHORIZATION erp_app;

-- V1 commence par "CREATE SCHEMA IF NOT EXISTS erp" : PostgreSQL verifie le droit
-- CREATE sur la base AVANT de constater que le schema existe. Les migrations
-- existantes ne sont jamais modifiees, d'ou ce droit (ADR-004).
GRANT CREATE ON DATABASE causebugmlops TO erp_app;

-- aiops_reader : visibilite du schema + SELECT sur tout objet que erp_app creera.
GRANT USAGE ON SCHEMA erp TO aiops_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE erp_app IN SCHEMA erp
    GRANT SELECT ON TABLES TO aiops_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE erp_app IN SCHEMA erp
    GRANT SELECT ON SEQUENCES TO aiops_reader;
SQL
