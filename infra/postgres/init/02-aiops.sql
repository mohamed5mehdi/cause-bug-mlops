-- ----------------------------------------------------------------------------
-- Espace de travail d'aiops-engine (ADR-011).
-- Execute UNE SEULE FOIS par l'image PostgreSQL, a la creation du volume,
-- apres 01-roles.sh (ordre alphabetique), avec le super-utilisateur.
--
--   - extension pgvector : sa creation exige le super-utilisateur ; elle sert
--     aux embeddings a partir de J5 ;
--   - schema aiops : propriete d'aiops_reader, qui y cree et ecrit ses tables.
--     aiops_reader reste en LECTURE SEULE sur le schema erp.
-- ----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS aiops AUTHORIZATION aiops_reader;
