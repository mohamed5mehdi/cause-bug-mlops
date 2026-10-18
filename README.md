# Cause.Bug.MLOps

Plateforme AIOps d'analyse automatique de cause racine (Automated Root Cause
Analysis) appliquée à un simulateur de processus d'achats (*procurement
workflow simulator*) : workflow de validation hiérarchique et gouvernance
budgétaire.

> **Statut : en construction.** Ce README décrit uniquement ce qui existe
> réellement dans le dépôt. Les fonctionnalités prévues sont listées à part.

## Principe

1. `erp-simule` (Java 21 / Spring Boot 3.4.5 / PostgreSQL 16) exécute un
   workflow d'achat interne et émet des événements de trace techniques.
2. `aiops-engine` (Python / FastAPI) ingère ces traces, les indexe avec
   pgvector et, sur demande, produit un diagnostic de cause racine structuré
   via l'API Gemini (approche RAG).

## Livré

| Module | Contenu |
|---|---|
| `erp-simule` | Modèle de domaine JPA, migrations Flyway V1 à V4, couche service du workflow d'achat interne, sonde de santé, image Docker, tests sur PostgreSQL éphémère |
| Infrastructure | `docker-compose.yml` : PostgreSQL 16 + pgvector, rôles séparés (`erp_app` propriétaire du schéma `erp`, `aiops_reader` en lecture seule) |
| API REST | Workflow Achat Interne de bout en bout sous `/api/demandes` : création, soumission, validations N+1 / technique / AMG / DAF / DG, valorisation avec engagement budgétaire, génération du Purchase Order ; données de démonstration (profil `demo`) |

## Lancement

Prérequis : Docker avec Compose v2.

```bash
cp .env.example .env      # puis remplacer chaque valeur par un mot de passe
docker compose up -d --build
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

PostgreSQL est exposé sur le port hôte `5435`.

## Prévu (non livré)

- Réponses d'erreur structurées (`ProblemDetail`) et logs corrélés par demande (MDC)
- Émission des traces d'exécution depuis `erp-simule`
- Module `aiops-engine` : ingestion, indexation pgvector, `POST /diagnose`
- Injection de pannes et jeu d'évaluation chiffré
- Tableau de bord `dashboard-aiops`

## Journal des décisions

Voir [docs/decisions-and-learnings.md](docs/decisions-and-learnings.md).

## Licence

[MIT](LICENSE)
