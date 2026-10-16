# erp-simule — Cause.Bug.MLOps (Phase 2)

Module Java 21 / Spring Boot 3.4.5. Domaine reduit et fidele : Achat Interne
(workflow N+1 -> Technicien -> Acheteur -> AMG -> DAF -> DG + ajustement
budgetaire Sous-Famille/Famille) et Achat de Pieces (verification automatique
de stock, chemin En Stock / Rupture).

## Ce qui est livre dans cette phase

- 14 entites JPA (`domain/`), 9 enums (`domain/common/`), 5 repositories
  (`repository/`), migration Flyway complete (`db/migration/V1__init_schema.sql`).
- Strategie de verrouillage explicite : `PESSIMISTIC_WRITE` sur
  `SousFamilleBudgetaire`, `FamilleBudgetaire` et `Article` (bugs candidats n1/n2
  pour le futur module de fault injection) ; `@Version` (optimiste) sur
  `DemandeAchatInterne` et `PurchaseOrder`.
- `DemandeAchatInterne.id` (UUID) sert directement de Business-Correlation-ID
  pour la strategie de tracabilite distribuee definie en Phase 1.
- `HistoriqueValidation` (piste d'audit fonctionnelle) est explicitement
  distincte de `transaction_trace_event` (donnee technique de diagnostic,
  Phase 1) : la premiere est proprietaire d'erp-simule, la seconde d'aiops-engine.

## Explicitement hors perimetre de cette livraison

- Couche service / logique metier (transitions d'etat, moteur de workflow).
- Couche REST (controllers, DTO, mapping).
- Aspect AOP + filtre MDC de tracabilite (specifies en Phase 1, a implementer
  en Phase 3).
- Module de fault injection.
- `docker-compose.yml`, `aiops-engine`, `dashboard-aiops`.

## Choix delegue

Outil de migration : **Flyway** retenu (SQL brut versionne, plus lisible pour
un audit humain que l'abstraction XML/YAML de Liquibase, et plus idiomatique
avec l'ecosysteme Spring Boot).
