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
  - Description du `pom.xml` reformulée : « ERP d'achats générique ».
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
