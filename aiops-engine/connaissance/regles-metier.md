# Règles métier du simulateur d'achats

Corpus de connaissance du diagnostic (ADR-013). Chaque section `## R-xx` est un
document vectorisé séparément (type `REGLE_METIER`). Les règles décrivent le
comportement **attendu** du processus ; la source citée est le code qui l'applique
dans `erp-simule`. Un écart entre la règle et ce qu'ont fait les traces est un
indice de cause racine.

## R-01 Cycle de vie d'une demande d'achat interne
Une demande d'achat interne suit une machine à états stricte, dans cet ordre :
BROUILLON → SOUMISE → VALIDEE_N1 → VALIDEE_TECHNIQUE → EN_VALORISATION →
VALIDEE_AMG → VALIDEE_DAF → VALIDEE_DG → PO_GENERE. Une décision de refus à une
étape de validation fait passer la demande à REJETEE. Aucune étape ne peut être
sautée ni rejouée. Source : DemandeAchatInterneService.

## R-02 Soumission
Seule une demande au statut BROUILLON peut être soumise ; elle passe alors à
SOUMISE et la date de soumission est enregistrée. Toute autre tentative est une
action hors séquence (TransitionStatutInvalideException, HTTP 409).
Source : DemandeAchatInterneService.soumettre.

## R-03 Validation hiérarchique N+1
Seule une demande SOUMISE peut être validée par le manager N+1. Une validation
la fait passer à VALIDEE_N1 ; un refus la fait passer à REJETEE avec l'étape et le
motif du rejet. Chaque décision est inscrite dans la piste d'audit.
Source : DemandeAchatInterneService.validerN1.

## R-04 Validation technique
La validation technique intervient après la validation N+1 : une demande
VALIDEE_N1 passe à VALIDEE_TECHNIQUE. Elle ne s'applique pas au flux ACHAT_PIECE.
Source : DemandeAchatInterneService.validerTechnique.

## R-05 Valorisation par l'acheteur
Seule une demande VALIDEE_TECHNIQUE peut être valorisée. L'acheteur saisit le
prix unitaire HT de chaque ligne, choisit un fournisseur existant et le taux de
TVA (compris entre 0 et 1, par exemple 0,20). Le montant TTC vaut la somme des
(quantité × prix unitaire HT) multipliée par (1 + taux de TVA). Pour un achat
interne, la valorisation déclenche l'engagement budgétaire (R-06) puis fait
passer la demande à EN_VALORISATION. Source : DemandeAchatInterneService.valoriser.

## R-06 Gouvernance budgétaire préventive
L'engagement budgétaire porte sur une sous-famille budgétaire (enveloppe). La
sous-famille est verrouillée en écriture (verrou pessimiste) pendant le contrôle.
Si le montant disponible est inférieur au montant TTC, l'engagement est refusé
(BudgetInsuffisantException, HTTP 422) avec le montant manquant, et toute la
valorisation est annulée : rien n'est engagé et la demande reste
VALIDEE_TECHNIQUE. Sinon, le montant engagé de la sous-famille augmente
exactement du montant TTC. Source : BudgetGovernanceService.verifierEtEngager.

## R-07 Intégrité des enveloppes budgétaires
Pour chaque sous-famille, le montant disponible (alloué moins engagé) ne doit
jamais devenir négatif. Un même montant TTC ne doit être engagé qu'une seule fois
par demande. Un engagement en double ou un disponible négatif est une
incohérence de données.

## R-08 Validations AMG, DAF et DG
La validation AMG n'est possible que depuis EN_VALORISATION (→ VALIDEE_AMG), la
validation DAF que depuis VALIDEE_AMG (→ VALIDEE_DAF) et la validation DG que
depuis VALIDEE_DAF (→ VALIDEE_DG). Les décisions AMG et DAF sont inscrites dans
la piste d'audit ; un refus fait passer la demande à REJETEE.
Source : DemandeAchatInterneService.validerAmg, validerDaf, validerDg.

## R-09 Génération du bon de commande (Purchase Order)
Un bon de commande n'est généré que pour une demande VALIDEE_DG (ou APPROUVEE
pour le flux ACHAT_PIECE), dotée d'un fournisseur sélectionné, d'au moins une
ligne et d'un montant TTC valorisé. Son montant total est égal au montant TTC de
la demande ; il reçoit un numéro unique PO-AAAAMMJJ-NNNN et la demande passe à
PO_GENERE. Un montant TTC absent à ce stade signifie que la valorisation a été
contournée. Source : PurchaseOrderService.genererDepuisDemande.

## R-10 Piste d'audit
Chaque décision de validation (N+1, AMG, DAF) est enregistrée dans l'historique de
validation avec l'acteur, l'étape, la décision et l'identifiant du dossier
(businessCorrelationId). L'historique est la preuve fonctionnelle du parcours ; il
doit être cohérent avec les traces techniques d'exécution.
Source : HistoriqueValidationService.

## R-11 Contrôle de stock (flux ACHAT_PIECE)
Pour une demande de pièces, chaque article est verrouillé en écriture pendant le
contrôle. Si la quantité en stock est inférieure à la quantité demandée, la
sortie de stock est refusée (StockInsuffisantException, HTTP 422) ; sinon le stock
diminue de la quantité demandée. Source : StockGovernanceService.verifierStock.

## R-12 Concurrence et verrous
Les demandes et les bons de commande portent un numéro de version (verrou
optimiste) : deux modifications simultanées du même objet provoquent un conflit
(HTTP 409). Les enveloppes budgétaires et les articles sont protégés par un
verrou pessimiste : une opération concurrente attend la libération du verrou ou
échoue si l'attente est trop longue (HTTP 503). Une attente anormalement longue
ou un échec de verrou relève de la base de données.
