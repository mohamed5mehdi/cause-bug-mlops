# erp-simule

Système observé de Cause.Bug.MLOps : un simulateur de processus d'achats
(*procurement workflow simulator*) en Java 21 / Spring Boot 3.4.5 /
PostgreSQL 16. Il ne connaît pas l'existence du pipeline de diagnostic.

## Domaine

- **Achat Interne** : validation hiérarchique N+1 → Technicien → Acheteur
  (valorisation et engagement budgétaire) → AMG → DAF → DG, puis génération du
  Purchase Order. Ajustements budgétaires Sous-Famille / Famille.
- **Achat de Pièces** : vérification automatique du stock (chemin En stock /
  Rupture) et budget pièces annuel avec contrôle de l'équation budgétaire.

## Ce qui est livré

- 14 entités JPA, migrations Flyway V1 à V4 (`ddl-auto: validate` : Hibernate
  vérifie le schéma au démarrage, sans jamais le modifier).
- Couche service du workflow (machine à états, verrous `PESSIMISTIC_WRITE` sur
  les enveloppes budgétaires et le stock, `@Version` sur `DemandeAchatInterne`
  et `PurchaseOrder`).
- Sonde de santé `/actuator/health` et image Docker (`Dockerfile`).
- Tests :
  - `ApplicationContextSmokeTest` : démarrage complet contre un PostgreSQL
    éphémère (Testcontainers) ;
  - `ConstatsBlocCVerificationTest` : constats sur le workflow d'ajustement et
    la concurrence budgétaire (nécessite un PostgreSQL et `TEST_PG_PASSWORD`).

## Défaut connu, conservé volontairement

`DemandeAjustementService.confirmerAcheteur` permet à une demande d'atteindre
`VALIDEE_DG` sans valorisation ; la génération du PO échoue ensuite. Ce défaut
est conservé comme scénario de panne pour la démonstration du diagnostic
(voir `docs/decisions-and-learnings.md`, ADR-002).

## Prévu (non livré)

- API REST du workflow et jeu de données de démonstration.
- Traçabilité technique (MDC, émission d'événements de trace).
- Module d'injection de pannes.

## Lancement

Depuis la racine du dépôt : `docker compose up -d --build` (voir le README racine).

Tests : `./mvnw test -Dtest=ApplicationContextSmokeTest` (Docker requis).
