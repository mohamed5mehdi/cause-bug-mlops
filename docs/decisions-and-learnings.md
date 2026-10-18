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
