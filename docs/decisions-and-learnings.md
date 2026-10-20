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

---

## ADR-007 — Workflow complet par HTTP (J2b) : données de démo, TVA bornée, PO transactionnel

- **Date** : 2026-10-18
- **Statut** : accepté
- **Contexte** : le parcours BROUILLON → PO_GENERE exige des données de référence
  (un utilisateur par rôle, fournisseur, enveloppes budgétaires) et expose un piège :
  `PurchaseOrderService.genererDepuisDemande(DemandeAchatInterne)` attend une entité
  attachée à une session ouverte.
- **Décision** :
  - Données de démo en migration Flyway **répétable** `db/demo/R__donnees_demo.sql`,
    idempotente (ON CONFLICT / NOT EXISTS), chargée uniquement sous le profil `demo`
    (activé par Docker Compose). Personnes et sociétés fictives.
  - 8 endpoints de workflow ; chacun délègue au service, qui contrôle le statut, puis
    renvoie l'état relu. L'en-tête `X-Utilisateur-Id` n'est exigé que là où le service
    enregistre un acteur (N+1, AMG, DAF).
  - Taux de TVA borné à [0 ; 1] dans `ValorisationRequest` (option A) : une saisie en
    pourcentage (20) est refusée en 400. La panne P2 sera injectée en aval de l'API (J8).
  - Nouvelle variante `genererDepuisDemande(UUID)` qui charge la demande dans sa propre
    transaction ; la variante existante n'est pas modifiée.
- **Alternatives écartées** : insertions manuelles de données (à refaire à chaque base) ;
  TVA non bornée dans l'API (API volontairement mal protégée) ; transaction dans le
  contrôleur.
- **Conséquences (vérifiées sur la pile Compose)** :
  - Parcours HTTP complet → `PO_GENERE`, PO `PO-20261018-1000`, 4 536,00 TTC
    (HT 3 780,00 × 1,20) ; 2 lignes de PO rattachées aux lignes de demande.
  - Sous-famille INFO-MAT : 4 536,00 engagés une seule fois (la tentative TVA = 20,
    refusée en 400, n'a rien engagé) ; rejeu du script de démo : 0 doublon.
  - Historique : 3 décisions (N+1, AMG, DAF) avec `business_correlation_id` = id de la
    demande ; aucune trace pour les étapes technique et DG (défaut E7, connu).
  - Action hors séquence (validation DG sur une demande PO_GENERE) → **500** : le
    service lève `IllegalStateException`. Traduction en 409 prévue à J2c.
  - Observation à arbitrer : l'engagement est porté par la sous-famille seule ;
    `famille_budgetaire.montant_engage` reste à 0. À clarifier (règle de consolidation
    Famille / Sous-Famille) avant le jalon des pannes.

---

## ADR-008 — Erreurs lisibles (ProblemDetail) et corrélation des logs (J2c)

- **Date** : 2026-10-18
- **Statut** : accepté
- **Contexte** : les refus métier sortaient en 500 (`IllegalStateException`) ou en corps
  Spring par défaut, sans identifiant permettant de relier une réponse à ses logs. Les
  services signalaient « introuvable » et « mauvais statut » avec des exceptions Java
  génériques, indiscernables d'un vrai défaut.
- **Décision** (option B) :
  - Deux exceptions métier : `RessourceIntrouvableException` (404) et
    `TransitionStatutInvalideException` (409, hérite d'`IllegalStateException` pour ne pas
    casser les appelants existants). Seules les gardes de statut et les recherches par
    identifiant sont converties ; les incohérences internes (montant non valorisé,
    fournisseur absent…) restent des `IllegalStateException` → **500**.
  - `GestionnaireErreursApi` (`@RestControllerAdvice` + `ResponseEntityExceptionHandler`) :
    400 détaillé (liste `erreurs`), 404, 409 (hors séquence, conflit `@Version`), 422
    (budget, stock), 503 + `Retry-After` (verrou pessimiste), 500 sans fuite du message
    technique. Chaque corps porte `code`, `requestId` et, si connu, `businessCorrelationId`.
  - `FiltreRequestId` (écrit par Mehdi) : `X-Request-Id` repris s'il respecte
    `^[A-Za-z0-9-]{1,64}$`, sinon UUID généré ; renvoyé au client ; MDC nettoyé en
    `finally`. `IntercepteurCorrelationMetier` : `businessCorrelationId` = `{id}` de la
    route s'il est un UUID ; posé par le contrôleur après une création.
- **Alternatives écartées** : option A (`IllegalStateException` → 409 globalement) : une
  panne injectée qui lève cette exception aurait été maquillée en refus métier, faussant
  le signal du moteur de diagnostic.
- **Conséquences (vérifiées)** :
  - Tests : 15/15 (`FiltreRequestIdTest` 6, `GestionnaireErreursApiTest` 9), exécutés
    sur une copie hors espace de travail.
  - Pile Compose : 404, 409 (`statutActuel: PO_GENERE`), 400 (`lignes`, `justification`),
    400 en-tête manquant, 422 (`montantManquant: 20000.00` sur INFO-LOG) ; après le 422,
    `montant_engage` d'INFO-LOG reste 0,00 et la demande reste `VALIDEE_TECHNIQUE`
    (rollback effectif). Logs : `reqId=demo-422 corrId=<id demande>`.
  - Non vérifié : le 503 en conditions réelles (l'indication de délai de verrou de
    3 000 ms est-elle appliquée par PostgreSQL ?). À tester avec deux transactions
    concurrentes avant le jalon des pannes.
  - Point de vigilance : le log WARN d'un 400 de validation reprend les valeurs rejetées
    saisies par le client (risque d'injection dans les logs) ; à durcir.
  - Donnée de démonstration ajoutée : une demande « Licences logicielles annuelles »
    au statut `VALIDEE_TECHNIQUE` (scénario 422).

---

## ADR-009 — Correction E5 (décision typée) et test du parcours HTTP (J2d)

- **Date** : 2026-10-18
- **Statut** : accepté
- **Contexte** : `HistoriqueValidationService.enregistrerDecision(..., String, ...)`
  convertissait toute décision inconnue en `VALIDE`. Le défaut touchait tout le flux
  d'ajustement budgétaire : `DemandeAjustementService` enregistrait
  `"SOUMISSION_AJUSTEMENT"`, `"CONFIRMATION"` et `"FINALISATION"`, absentes de
  l'énumération, donc tracées « VALIDE » : la piste d'audit mentait sans erreur.
  Par ailleurs, le parcours HTTP de J2b n'était vérifié qu'à la main.
- **Décision** (option B) :
  - `DecisionValidation` reçoit `SOUMISSION_AJUSTEMENT`, `CONFIRMATION`, `FINALISATION`.
  - La surcharge `String` est supprimée : tous les appelants passent l'énumération ;
    `decider()` convertit avec `valueOf` un texte déjà restreint à VALIDE / REJETE.
    Une décision inconnue ne compile plus.
  - Aucune migration : `historique_validation.decision` est un `VARCHAR(30)` sans
    contrainte CHECK (valeur la plus longue : 21 caractères). Les lignes déjà écrites
    ne sont pas réécrites.
  - `ParcoursDemandeAchatHttpTest` : parcours BROUILLON → PO_GENERE via MockMvc sur la
    pile réelle (Testcontainers PostgreSQL 16, profil `demo`), puis contrôles en base.
  - `erp-simule/http/parcours-demande-achat.http` : même parcours, rejouable dans l'IDE.
- **Alternatives écartées** : option A (lever une exception sur texte inconnu) : le
  flux d'ajustement aurait échoué à chaque appel.
- **Conséquences (vérifiées)** :
  - `test-compile` OK ; tests exécutés par Mehdi sur une copie hors espace de travail :
    19/19 (parcours 1, gestionnaire d'erreurs 9, filtre 6, démarrage 3), BUILD SUCCESS.
  - Le test de parcours prouve : 2 880,00 engagés exactement sur INFO-MAT (3 × 800 HT,
    TVA 20 %), PO à 2 880,00 TTC, historique `N1:VALIDE`, `AMG:VALIDE`, `DAF:VALIDE`,
    puis 409 `TRANSITION_STATUT_INVALIDE` sur une validation DG rejouée.
  - Le scénario de panne P3 (T1a) n'est pas corrigé ; sa trace d'audit porte désormais
    `CONFIRMATION` au lieu d'un faux `VALIDE`.
  - Le test fige le défaut E7 connu (aucune trace pour les étapes technique et DG) :
    à mettre à jour quand E7 sera corrigé.
  - Pas de test d'exécution du flux d'ajustement (non exposé en HTTP, test de constat
    dépendant d'une base externe) : la non-régression d'E5 repose sur le typage.
  - Constat annexe : `StockGovernanceConcurrencyIT` n'est jamais exécuté par
    `mvn test` (suffixe `IT` sans plugin failsafe).
  - Revue : la CLI Qodo n'est pas installée sur la machine ; relecture manuelle sur
    5 axes (correction, lisibilité, architecture, sécurité, performance), sans point
    bloquant. Le diff de `DemandeAjustementService` inclut un reformatage par l'IDE.

---

## ADR-010 — Trace d'exécution des services : table dédiée + pg_notify (J3)

- **Date** : 2026-10-18
- **Statut** : accepté
- **Contexte** : le moteur de diagnostic a besoin de la chronologie technique de chaque
  dossier (quel service, quand, combien de temps, succès ou échec). Deux contraintes :
  la trace d'un échec doit survivre au rollback métier, et aiops-engine peut être
  arrêté. D'après la documentation PostgreSQL 16 (NOTIFY), une notification émise dans
  une transaction annulée n'est pas remise, n'atteint que les sessions déjà en écoute,
  et sa charge doit rester sous 8 000 octets.
- **Décision** :
  - **Transport (option B)** : table `erp.trace_execution` (migration V5) ; un trigger
    `AFTER INSERT` émet `pg_notify('trace_execution', id)`. La notification ne porte
    que l'identifiant ; la donnée reste relisible en table.
  - **Granularité** : chaque méthode publique de `com.causebugmlops.erp.service`, en
    succès comme en échec. La panne P3 (FLUX_METIER) ne lève aucune exception : seule
    la séquence des appels la révèle.
  - **Isolation** : écriture en `REQUIRES_NEW`, jamais dans la transaction métier.
  - **Aspect le plus externe** (`HIGHEST_PRECEDENCE`) : il entoure le proxy
    transactionnel, donc un échec au commit est tracé ECHEC, pas SUCCES.
  - **Tampon par requête** : les appels imbriqués sont gardés en mémoire (ThreadLocal)
    et écrits en un seul lot à la fin de l'appel le plus externe, après la libération
    de la connexion métier : pas de deuxième connexion simultanée, pas de famine du
    pool (10 connexions).
  - Une erreur d'écriture de trace est journalisée et ignorée : la trace ne fait
    jamais échouer le métier.
- **Alternatives écartées** :
  - option A (`pg_notify` seul, charge complète) : traces perdues si l'écouteur est
    absent, taille bornée ;
  - traces sur exceptions seules : aveugle aux pannes de flux sans exception ;
  - écriture immédiate en `REQUIRES_NEW` à chaque appel imbriqué : deux connexions
    par requête pendant la transaction métier, risque de famine du pool sous charge.
- **Conséquences (vérifiées)** :
  - Tests : 20/20, exécutés par Mehdi (dont `TraceExecutionTest` : 422 annulé →
    engagement inchangé, traces `valoriser:ECHEC` et `verifierEtEngager:ECHEC`
    présentes, identifiant reçu par un client en écoute sur le canal).
  - Pile Compose : V5 appliquée (5 migrations) ; requête réelle `demo-j3-422` → 2
    traces ECHEC `BudgetInsuffisantException` lues avec `aiops_reader` ;
    `montant_engage` d'INFO-LOG reste 0,00 ; `DELETE` refusé à `aiops_reader`
    (`permission denied`).
- **Limites connues** :
  - `creerBrouillon` est tracé sans `businessCorrelationId` (l'id n'existe pas encore) ;
  - une trace en tampon est perdue si la JVM s'arrête brutalement en pleine requête ;
  - la lecture `GET /api/demandes/{id}` passe par le repository : non tracée ;
  - les durées du premier appel après démarrage incluent le préchauffage de la JVM
    (614 ms observés), à écarter des futures statistiques.

---

## ADR-011 — aiops-engine : ingestion des traces par LISTEN + rattrapage (J4)

- **Date** : 2026-10-18
- **Statut** : accepté
- **Contexte** : premier composant Python. Il doit recevoir les traces émises par le
  simulateur d'achats (ADR-010) sans jamais en perdre, y compris quand il est arrêté, et
  disposer d'un espace où écrire ses propres données (copie des traces, puis embeddings à
  J5). L'extension pgvector ne peut être créée que par le super-utilisateur, et les scripts
  d'initialisation PostgreSQL ne s'exécutent qu'à la création du volume.
- **Décision** :
  - Service FastAPI 0.146.0 / Uvicorn 0.54.0 / asyncpg 0.32.0 / pydantic-settings 2.16.0,
    image `python:3.12-slim` (version figée dans l'image, pas déduite de l'hôte), utilisateur
    non-root, Dockerfile en deux étapes (`runtime`, `test`).
  - `infra/postgres/init/02-aiops.sql` : `CREATE EXTENSION vector` et schéma `aiops`
    propriété d'`aiops_reader`, qui reste en lecture seule sur `erp`. Nom du rôle conservé.
  - Volume recréé (option A) pour appliquer ce script : la pile complète redémarre de zéro
    en une commande.
  - Table `aiops.transaction_trace_event` (copie, `source_id` UNIQUE) créée par un script
    idempotent au démarrage (pas d'outil de migration Python pour l'instant).
  - Écoute sur une **connexion dédiée hors pool** (une connexion rendue au pool perd ses
    écouteurs, documentation asyncpg) ; copie en SQL côté serveur (`INSERT … SELECT …
    ON CONFLICT DO NOTHING`).
  - **Rattrapage** à chaque (re)connexion, lancé **après** l'activation de l'écoute : aucune
    trace ne tombe entre les deux ; copie et rattrapage idempotents.
  - Reconnexion automatique avec attente croissante (1 s → 30 s max) ; `/health` répond 503
    tant que l'écoute est coupée.
  - Routes : `GET /health`, `GET /traces/{correlationId}` (chronologie d'un dossier).
  - Compose : `aiops-engine` démarre après `erp-simule` sain (Flyway crée la table écoutée).
- **Alternatives écartées** :
  - écoute sur une connexion du pool : écouteurs supprimés à la remise au pool ;
  - rattrapage par « id > max(source_id) » : manquerait une trace dont la transaction
    s'est validée après une trace d'id supérieur ;
  - application manuelle du SQL sur l'ancien volume (option B) : moins reproductible.
- **Conséquences (vérifiées)** :
  - pytest dans le réseau Compose : 10/10 (5 unitaires, 5 d'intégration : santé, trace
    notifiée puis copiée, rattrapage d'une trace supprimée, copie idempotente, UUID invalide
    → 422).
  - Redémarrage de PostgreSQL : `/health` en 503 pendant la coupure, de nouveau UP en ~2 s ;
    une trace émise ensuite est copiée.
  - Délai entre la fin d'un appel tracé et sa copie : 13 ms en moyenne, 27 ms au pire
    (4 traces mesurées : échantillon faible, indicatif).
- **Limites connues** :
  - le rattrapage relit toute la table source (suffisant au volume du MVP, à borner ensuite) ;
  - FastAPI répond 422 (et non 400) à un paramètre invalide : convention FastAPI, différente
    du simulateur Java ;
  - le compteur `traces_ingerees` de `/health` est en mémoire, remis à zéro au redémarrage.

---

## ADR-012 — Embeddings gemini-embedding-2 et base de connaissance pgvector (J5)

- **Date** : 2026-10-19
- **Statut** : accepté
- **Contexte** : le diagnostic RAG (J6) doit retrouver, par le sens, les incidents passés et
  les règles métier proches d'une panne. La documentation Gemini (mise à jour le 2026-10-09,
  consultée le 2026-10-19) présente un modèle plus récent que celui noté le 2026-10-16 :
  `gemini-embedding-2`, qui normalise lui-même les vecteurs de 768, n'accepte pas de
  `task_type` (préfixes dans le texte) et fusionne en UN vecteur plusieurs textes envoyés
  dans un même appel. Les espaces vectoriels des deux modèles sont incompatibles.
- **Décision** :
  - Modèle `gemini-embedding-2`, dimension 768, SDK officiel `google-genai` 2.31.0, client
    asynchrone ; **un texte par appel** (pas de fusion involontaire) ; normalisation L2
    appliquée quand même ; dimension vérifiée (≠ 768 → erreur explicite).
  - Préfixes : documents `title: … | text: …`, requêtes `task: search result | query: …`.
  - Seules les traces **ECHEC** sont vectorisées (les règles métier suivront à J6).
  - Table `aiops.document_vectorise` (`vector(768)`), unicité (type, référence, **modèle**),
    index **HNSW `vector_cosine_ops`** ; recherche par `<=>`, l'opérateur de l'index.
    Similarité affichée = 1 − distance cosinus.
  - Vectorisation en tâche de fond après chaque copie et à chaque reconnexion, sous verrou,
    idempotente ; interrompue à la première erreur d'API, reprise au déclenchement suivant.
  - Clé facultative : sans `GEMINI_API_KEY`, l'ingestion continue et `/health` indique
    `vectorisation: INACTIVE`. Route `GET /recherche?q=…&k=…` (503 si inactive).
- **Alternatives écartées** :
  - `gemini-embedding-001` : normalisation manuelle obligatoire, entrée limitée à 2 048 tokens ;
  - vectoriser toutes les traces : coût d'API sans valeur de diagnostic pour les succès ;
  - bibliothèque Python `pgvector` : inutile, le littéral texte `'[x,…]'::vector` suffit.
- **Conséquences (vérifiées)** :
  - pytest : 20/20 (dont 2 tests contre la vraie API), deux passages consécutifs verts.
  - Faux modèle à géométrie connue : seuls les échecs sont vectorisés, idempotence, et le
    plan d'exécution montre l'usage de `idx_document_vectorise_hnsw`.
  - API réelle : vecteurs de 768, norme 1 ; un vrai 422 budget du simulateur est retrouvé
    par `/recherche` avec une question reformulée.
  - Mesure sur la pile : « l'enveloppe budgétaire ne couvre pas le montant de la commande »
    → 0,755 avec l'échec budget ; « recette de gâteau au chocolat » → 0,512 avec le même
    document. Une phrase sans rapport n'est donc pas proche de 0 : il faut **comparer** les
    similarités, pas fixer un seuil absolu sans calibration (à faire à J9).
  - Leçon de test : avec une base partagée, un test ne doit pas supposer un rang exact ; le
    test à faux modèle vérifie désormais une propriété et nettoie aussi les vecteurs
    éventuellement créés par le service en marche.
- **Limites connues** : quotas et tarif de l'API non vérifiés (avant J9) ; plusieurs
  déclenchements simultanés de vectorisation s'enchaînent sous verrou (suffisant au MVP).

---

## ADR-013 — Diagnostic RAG : POST /diagnose avec Gemini et sortie structurée (J6)

- **Date** : 2026-10-19
- **Statut** : accepté
- **Contexte** : cœur du projet. Documentation Gemini consultée le 2026-10-19 :
  `generateContent` reste « entièrement supportée » ; l'Interactions API est recommandée
  pour les nouveaux développements mais **stocke les échanges par défaut** ; pour les
  nouveaux projets, la page *Models* recommande « 3.5 Flash-Lite ou 3.8 Flash » ; la page
  *Structured outputs* demande de toujours valider les valeurs côté application.
- **Décision** :
  - `generate_content` (sans état, rien n'est conservé côté Google entre deux appels),
    schéma Pydantic `DiagnosticGemini` en `response_schema` : `cause_racine`, `confiance`
    ∈ [0 ; 1], `composant` ∈ {CODE_JAVA, BASE_DE_DONNEES, FLUX_METIER}, `preuves`.
  - Modèle principal `gemini-3.8-flash`, secours `gemini-3.5-flash-lite` (configurables).
  - **Résilience** : erreurs passagères (429, 500, 503) → 3 tentatives avec attente
    croissante, puis bascule vers le secours ; erreur définitive (400, 401, 403, 404) →
    échec immédiat. Le modèle réellement utilisé est enregistré.
  - **Garde-fous** : `response.parsed is None` → échec explicite (avec `finish_reason`) ;
    revalidation Pydantic des bornes ; chaque preuve citée doit exister dans le dossier,
    sinon elle est retirée et comptée (signal d'hallucination).
  - **Dossier d'enquête numéroté** : chronologie (T1…), état de la demande et piste
    d'audit (lus en lecture seule dans `erp`), incidents passés proches (I1…, en excluant
    les traces du dossier lui-même), règles métier proches (R-xx).
  - **Corpus** : 12 règles rédigées à partir du code (`aiops-engine/connaissance/
    regles-metier.md`, source citée pour chacune), vectorisées au démarrage en tâche de
    fond ; une règle modifiée est revectorisée, une règle supprimée est retirée.
  - Chaque diagnostic est enregistré dans `aiops.diagnostic` (résultat, modèle, latence,
    jetons) pour l'évaluation (J9) et le tableau de bord.
  - Codes : 404 sans trace, 502 si le modèle ne fournit pas de réponse exploitable,
    503 si l'IA est inactive.
- **Alternatives écartées** : Interactions API (stockage par défaut, réécriture de la
  spécification) ; PDF métier comme corpus (risque de noms internes, emplacement inconnu) ;
  un seul modèle sans secours (le 2026-10-19, `gemini-3.8-flash` répondait 503 « forte
  demande » à chaque appel pendant les tests).
- **Conséquences (vérifiées)** :
  - pytest : 31/31, dont 13 nouveaux (découpage des 12 règles, garde-fous `parsed None`
    et hors bornes, bascule 503 → secours, erreur 400 sans nouvelle tentative, preuves
    inventées retirées, dossier sans trace, bout en bout réel).
  - Bout en bout réel (refus budgétaire de 2 160 000 TTC sur INFO-LOG) : `FLUX_METIER`,
    cause « montant disponible de la sous-famille inférieur au TTC lors de la
    valorisation… refus conforme à la gouvernance budgétaire », preuves T5, T6, R-06, R-05,
    I1 (toutes valides, 0 rejetée), modèle de secours après 3 × 503, 17,9 s, 1 418 jetons
    en entrée et 128 en sortie.
- **Limites connues** :
  - confiance de 1,0 sur ce cas : le modèle peut être surconfiant ; calibration à J9 ;
  - quand le principal est indisponible, les tentatives ajoutent ~6 s à chaque appel
    (un disjoncteur mémorisant l'indisponibilité est une amélioration possible) ;
  - recherche filtrée sur l'index HNSW : avec beaucoup de documents, un filtre peut
    renvoyer moins de k résultats (à surveiller lors du passage à l'échelle) ;
  - quotas de l'offre gratuite non vérifiés.

## ADR-014 — dashboard-aiops : React 19, même origine via nginx, types générés (J7)

- **Date** : 2026-10-20
- **Statut** : accepté
- **Contexte** : la spécification initiale imposait React 18. Registre npm consulté le
  2026-10-19 : react 19.3.1 (dernière 18 : 18.3.1), vite 8.3.4, tailwindcss et
  @tailwindcss/vite 4.3.3, typescript 7.0.2, openapi-typescript 7.13.0 dont la
  dépendance pair exige `typescript ^5.x`. aiops-engine n'a ni CORS, ni route listant
  les dossiers, ni route relisant `aiops.diagnostic`.
- **Décision** :
  - React 19.3 (version maintenue) au lieu de React 18 ; la spécification est mise à jour.
  - TypeScript fixé à 5.9.3 (compatible openapi-typescript), mode strict, aucun `any`.
  - Tailwind 4 via `@tailwindcss/vite` (configuration en CSS, sans tailwind.config.js).
  - Même origine : nginx sert le tableau de bord et relaie `/api/*` vers aiops-engine ;
    en développement, le proxy de Vite joue le même rôle. Aucun CORS à ouvrir.
  - Types TypeScript générés depuis `/openapi.json` (openapi-typescript) : une dérive
    Pydantic ↔ TypeScript casse la compilation au lieu de casser l'écran.
  - J7a ajoute `GET /dossiers` et `GET /diagnostics/{id}` (lecture seule).
- **Alternatives écartées** : React 18 (react.dev documente la branche 19 comme
  version courante) ;
  CORS sur FastAPI (une surface de configuration de plus, inutile en même origine) ;
  types recopiés à la main (dérive silencieuse) ; TypeScript 7 (incompatible
  openapi-typescript à ce jour).
- **Conséquences** : un service Compose de plus (dashboard-aiops) ; la génération des
  types demande aiops-engine démarré ou un `openapi.json` exporté.

## ADR-015 — Délais maximaux des appels Gemini et budget de POST /diagnose

- **Date** : 2026-10-20
- **Statut** : accepté
- **Contexte** : le 2026-10-20, un appel `gemini-3.8-flash` a mis 178 986 ms à répondre ;
  les clients google-genai (diagnostic et embeddings) étaient créés sans délai, donc
  `/diagnose` pouvait bloquer indéfiniment. Vérifié dans google-genai 2.31.0 (code du
  SDK dans l'image) : `HttpOptions.timeout` est en **millisecondes** ; un délai dépassé
  côté client lève `httpx.TimeoutException` (pas `APIError`) ; le SDK ne fait aucune
  nouvelle tentative sans `retry_options`.
- **Décision** :
  - délai de 30 s par appel Gemini (diagnostic et embeddings), configurable
    (`DELAI_APPEL_GEMINI_S`) ;
  - 2 tentatives par modèle (au lieu de 3), configurable (`TENTATIVES_GEMINI`) ;
  - délai dépassé = erreur passagère : `httpx.TimeoutException` **et** code 504 ;
  - budget global de 130 s sur `POST /diagnose` (`asyncio.timeout`), au-delà : 504.
- **Apprentissage** : le SDK transmet le délai au serveur (en-tête `X-Server-Timeout`) ;
  Google coupe alors lui-même la génération et renvoie un **504**, avant que le délai
  httpx n'expire. Sans 504 dans les codes passagers, il n'y avait pas de bascule.
- **Alternatives écartées** : 3 tentatives par modèle (pire cas 186 s, écran bloqué
  plus de 3 min) ; `retry_options` du SDK (relances cumulées avec les nôtres).
- **Conséquences (vérifiées)** :
  - pytest 39/39, dont 3 nouveaux (délai client → secours, 504 serveur → secours,
    délai partout → échec explicite) ;
  - bout en bout réel : 3.8-flash coupé deux fois à 30 s (504), bascule vers
    3.5-flash-lite (503 puis 200), diagnostic FLUX_METIER en 72,9 s.
- **Limites** : pire cas 122 s de génération, plus les embeddings, borné à 130 s ;
  un diagnostic coupé en 504 n'est pas enregistré ; une génération abandonnée est
  probablement facturée (hypothèse non vérifiée).

## ADR-016 — Squelette dashboard-aiops (J7b) et exception du contrôle des noms

- **Date** : 2026-10-20
- **Statut** : accepté
- **Décision** :
  - dashboard-aiops livré : React 19.3, TypeScript 5.9 strict, Tailwind 4, ESLint 10
    (`no-explicit-any` en erreur) ; image en deux étapes `node:24.22-alpine` (build) puis
    `nginx:1.30-alpine` (service) ; nginx relaie `/api/*` vers aiops-engine avec
    `proxy_read_timeout 140s` (au-delà du budget de 130 s, ADR-015) ; port hôte 5173.
  - Le contrôle des noms interdits exclut `dashboard-aiops/src/api/schema.d.ts` : son
    en-tête, qui signale une génération automatique par openapi-typescript, nomme un outil réellement utilisé
    (traducteur OpenAPI → TypeScript, sans IA) et serait effacé à chaque régénération.
- **Vérifications** : tsc + ESLint + build Vite passent ; un fichier piège avec `any` est
  refusé ; via nginx : `GET /` 200, `/api/health` 200, `/api/dossiers` 200, route
  inconnue → index.html (200) ; 4 conteneurs « healthy ».

## ADR-017 — Écrans du tableau de bord : dossiers, chronologie, diagnostic (J7c)

- **Date** : 2026-10-20
- **Statut** : accepté
- **Décision** :
  - trois zones : liste des dossiers (`GET /dossiers`, échecs mis en évidence),
    chronologie numérotée T1…Tn (`GET /traces/{id}`, même numérotation que le dossier
    d'enquête envoyé à Gemini, pour retrouver les preuves citées), panneau de diagnostic
    (`POST /diagnose/{id}` et historique `GET /diagnostics/{id}`) ;
  - un seul diagnostic à la fois : bouton désactivé et chronomètre pendant l'attente
    (jusqu'à 130 s), pour ne jamais payer deux appels Gemini par un double clic ;
  - codes 404, 502, 503 et 504 traduits en messages lisibles ;
  - état de chaque zone en union discriminée (`en-cours | erreur | pret`) ; changement
    de dossier = composants remontés (`key`), réponse obsolète ignorée.
- **Vérifications (navigateur réel, via nginx)** : liste, chronologie T1–T6 avec
  exceptions `BudgetInsuffisantException` sur T5 et T6, chronomètre visible, un seul
  `POST /api/diagnose` dans le journal réseau, verdict FLUX_METIER avec preuves
  T5, T6, R-06, R-05 (gemini-3.8-flash, 42,9 s), historique passé de 1 à 2.
- **Limites** : confiance affichée à 100 % (calibration prévue à J9) ; pas de tests
  automatisés de l'interface à ce stade.
