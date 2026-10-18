# Journal des décisions et apprentissages

Format ADR court : date, contexte, décision, alternatives écartées, conséquences.

---

## ADR-001 — Structure du dépôt, outillage de build et licence

- **Date** : 2026-10-16
- **Statut** : accepté
- **Contexte** : la copie de travail ne contenait qu'un module `erp-simule`, sans
  dépôt git, sans `.gitignore`, sans Maven Wrapper (Maven absent du PATH), et le
  `pom.xml` citait nommément le système de l'employeur. Le dépôt sera public.
- **Décision** :
  - Monorepo unique, branche `main`. Les modules `aiops-engine` et
    `dashboard-aiops` seront créés au moment où ils contiendront du code réel.
  - Maven Wrapper 3.3.4 (type `only-script`, Maven 3.9.16) généré via l'image
    Docker officielle `maven:3.9-eclipse-temurin-21`, sans installation de Maven
    sur l'hôte.
  - `.gitattributes` : LF par défaut, CRLF pour les scripts Windows, LF imposé
    pour `mvnw` (exécuté dans des conteneurs Linux).
  - Licence MIT, titulaire Mohamed Mehdi El Jadaoui.
  - Description du `pom.xml` reformulée de façon générique, sans référence
    nominative (vocabulaire définitif fixé par l'ADR-005).
- **Alternatives écartées** :
  - Dossiers vides ou « placeholders » pour les modules futurs : Git ne versionne
    pas les dossiers vides, et ils annonceraient un contenu inexistant.
  - Installation de Maven via winget : dépendance supplémentaire sur l'hôte.
  - Licence Apache-2.0 : clause de brevets non nécessaire pour un projet portfolio.
- **Conséquences** : build reproductible par `./mvnw` ; aucun push avant le
  jalon J10 (scan de secrets et de références nominatives au préalable).

---

## ADR-002 — Statut des défauts T1a et E5

- **Date** : 2026-10-16
- **Statut** : accepté
- **Contexte** : l'état des lieux a confirmé deux défauts métier.
  - **T1a** : `DemandeAjustementService.confirmerAcheteur` fait passer la demande
    à `EN_VALORISATION` sans valorisation. La demande atteint `VALIDEE_DG` avec
    `montant_ttc` NULL, sans engagement budgétaire, et la génération du PO échoue
    (« Fournisseur doit etre selectionne »). Prouvé par
    `ConstatsBlocCVerificationTest#constatT1a` (29/29 hypothèses confirmées).
  - **E5** : `HistoriqueValidationService.enregistrerDecision(String)` convertit
    toute décision inconnue (`SOUMISSION_AJUSTEMENT`, `CONFIRMATION`,
    `FINALISATION`) en `VALIDE`, ce qui fausse la piste d'audit.
- **Décision** :
  - **T1a** : défaut connu, **conservé volontairement** comme panne P3
    (composant attendu : `FLUX_METIER`) pour la démonstration du diagnostic.
    Il ne doit pas être corrigé sans décision explicite.
  - **E5** : sera corrigé au jalon J2, pas avant.
- **Alternatives écartées** : corriger T1a maintenant (perte d'un scénario de
  panne réel et déjà prouvé) ; corriger E5 à J0 (hors périmètre de l'hygiène du
  dépôt).
- **Conséquences** : le README public présentera T1a comme défaut connu et
  conservé ; le test T1a sert de preuve de reproductibilité.

---

## ADR-003 — Démarrage d'erp-simule : défauts E1 et E2, garde-fou et Testcontainers

- **Date** : 2026-10-17
- **Statut** : accepté
- **Contexte** : erp-simule ne démarrait pas. Constaté en lançant l'application
  contre un PostgreSQL 16 jetable :
  - **E1** : `Schema-validation: missing column [ligne_demande_id] in table
    [erp.ligne_purchase_order]`. V1 avait créé `article_id NOT NULL`, jamais mappé,
    alors que l'entité `LignePurchaseOrder` référence la ligne de demande.
    Comparaison complète entités/schéma : aucun autre écart.
  - **E2** : `Not a managed type: class java.lang.Object` —
    `PoNumeroCompteurRepository extends Repository<Object, Long>`.
  - Les deux tests existants forçaient `ddl-auto=none` et remplaçaient le
    repository par un mock : ils ne pouvaient détecter ni E1 ni E2.
- **Décision** :
  - Migration `V4` : ajout de `ligne_demande_id NOT NULL` (FK + index), suppression
    de `article_id`. L'article reste accessible via la ligne de demande, et les
    lignes en texte libre (sans article) deviennent commandables.
  - `PoNumeroCompteurRepository` devient une classe `@Repository` appelant
    `erp.po_numero_compteur()` via `JdbcTemplate` (même nom, tests existants intacts).
  - Garde-fou `ApplicationContextSmokeTest` : contexte complet, `ddl-auto=validate`,
    PostgreSQL éphémère `pgvector/pgvector:pg16` via Testcontainers + `@ServiceConnection`.
  - `testcontainers.version` surchargé à **1.21.4** : la version gérée par Spring
    Boot 3.4.5 (1.20.6) échoue avec Docker Engine 29 (API minimale 1.44) ; les notes
    de version officielles indiquent que 1.21.4 rend la branche 1.x compatible.
- **Alternatives écartées** : garder `article_id` nullable (colonne morte) ;
  remapper l'entité sur l'article (perte de traçabilité) ; `Repository<PurchaseOrder,
  Long>` détourné ; Testcontainers 2.x (renommage des modules et des packages).
- **Conséquences et apprentissage** :
  - Vérifié : `docker compose up` → « Successfully applied 4 migrations… v4 »,
    `/actuator/health` = UP ; smoke test 3/3.
  - **Piège d'environnement** : l'extension Java de l'IDE (serveur Eclipse JDT)
    recompile en tâche de fond dans le même `target/classes` que Maven et y produit
    des `.class` invalides (`ClassFormatError: Invalid method Code length 0`).
    Preuve : la même commande Maven échoue dans le dossier du projet et réussit
    (3/3) sur une copie hors de l'espace de travail de l'IDE. L'image Docker n'est
    pas concernée (build isolé).

---

## ADR-004 — Rôles PostgreSQL et gestion des secrets locaux

- **Date** : 2026-10-17
- **Statut** : accepté
- **Contexte** : l'architecture sépare `erp_app` (écriture, schéma `erp`) et
  `aiops_reader` (lecture seule). `application.yml` contenait un mot de passe par
  défaut `changeme`.
- **Décision** :
  - Script `infra/postgres/init/01-roles.sh` (exécuté à la création du volume) :
    schéma `erp` appartenant à `erp_app` ; `aiops_reader` avec `USAGE` et `SELECT`
    par défaut sur les tables et séquences futures de `erp_app`.
  - `GRANT CREATE ON DATABASE` à `erp_app` : V1 commence par
    `CREATE SCHEMA IF NOT EXISTS erp`, et PostgreSQL vérifie ce droit avant de
    constater que le schéma existe (échec observé : `permission denied for
    database`). Les migrations existantes ne sont jamais modifiées.
  - Secrets : `.env.example` versionné, `.env` local ignoré par git ; `DB_PASSWORD`
    sans valeur par défaut (échec explicite si absent).
- **Alternatives écartées** : tout exécuter sous `postgres` ; modifier V1 ;
  `erp_app` propriétaire de la base (droits trop larges).
- **Conséquences** : vérifié avec `psql` — `aiops_reader` lit (`flyway_schema_history`
  : 4 lignes), mais `INSERT` et `CREATE TABLE` sont refusés ; `nspowner(erp) = erp_app`.
  Toute modification du script de rôles exige `docker compose down -v` (recréation
  du volume, donc perte des données locales).

---

## ADR-005 — Vocabulaire : « simulateur de processus d'achats », pas « ERP »

- **Date** : 2026-10-17
- **Statut** : accepté
- **Contexte** : erp-simule n'est pas un ERP. Il simule un seul processus métier :
  un workflow d'achat avec validation hiérarchique et gouvernance budgétaire. Le mot
  « ERP » surestimait le périmètre devant un public technique.
- **Décision** : dans la documentation, les README et les métadonnées (description du
  `pom.xml`), le système observé est désigné « simulateur de processus d'achats »
  (anglais : *procurement workflow simulator*), ou « processus métier » / « système
  observé ». Les identifiants techniques existants (`erp-simule`, schéma `erp`, rôle
  `erp_app`, package `com.causebugmlops.erp`) restent inchangés tant qu'un
  renommage n'est pas décidé explicitement.
- **Alternatives écartées** : renommer aussi les identifiants maintenant (coût élevé :
  migrations, rôles, packages, images, sans bénéfice fonctionnel pour le MVP).
- **Conséquences** : « ERP » n'est plus utilisé pour désigner le système observé
  (seul cet ADR le mentionne, pour l'écarter) ; les
  identifiants `erp*` restent visibles dans le code et la configuration.

---

## ADR-006 — Première API REST (J2a) : création et lecture d'une demande

- **Date** : 2026-10-18
- **Statut** : accepté
- **Contexte** : le simulateur n'exposait aucun point d'entrée HTTP ; la couche service
  existait mais n'était appelable que par les tests.
- **Décision** :
  - Package `api` (contrôleurs) et `api.dto` (formulaires et réponses), séparés du
    `dto` déjà utilisé par la couche service.
  - DTO en `record` Java : immuables, sans logique ; l'entité JPA n'est jamais exposée.
  - Validation de forme par Jakarta Bean Validation sur le DTO (`@Valid`) : une requête
    mal remplie est refusée en 400 avant tout appel au service. Les règles de fond
    (budget, statut) restent dans le service.
  - Lecture via `findDetailById` annoté `@EntityGraph` (demandeur, lignes, articles
    chargés en une requête), nécessaire car `open-in-view` est désactivé.
  - Acteur identifié par l'en-tête `X-Utilisateur-Id` : identification simulée, pas une
    authentification (limite assumée du MVP).
- **Alternatives écartées** : exposer l'entité (chargements lazy hors session) ;
  `@Transactional` dans le contrôleur (mélange des responsabilités) ; Spring Security
  dès maintenant (hors périmètre MVP).
- **Conséquences** : vérifié sur la pile Compose — POST valide → 201 + `Location`,
  GET → 200, `quantite = 0` → 400 (`Positive` sur `lignes[0].quantite`), en-tête absent
  → 400, utilisateur ou demande inconnus → 404 ; une seule demande créée en base.
  Le corps des erreurs reste le format Spring par défaut, sans détail lisible : traité
  à J2c (`ProblemDetail`).
