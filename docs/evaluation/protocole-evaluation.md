# Protocole d'évaluation du diagnostic (v2)

**Objet** : mesurer si le diagnostic désigne le bon composant **et** la bonne cause pour trois
pannes connues, et s'il s'abstient sur des cas sains. Deux séries identiques sont prévues :
**mesure 1** (référence, version actuelle) et **mesure 2** (après J9a). Seul le code du moteur
change entre les deux.

**Biais déclaré** : les pannes ont été conçues par l'auteur du moteur. Le résultat mesure la
cohérence du diagnostic sur ces cas, pas sa capacité à détecter des pannes inconnues.

**Historique** : un pilote de 3 essais (2026-10-21, `pilote-2026-10-21.csv`) a révélé des
variables non contrôlées ; il est conservé à titre de trace et n'entre dans aucun calcul.

## 1. Cas et vérité terrain

| Cas | Mécanisme | Composant attendu | Cause attendue |
|---|---|---|---|
| P1 | Une clôture budgétaire concurrente retient le verrou de l'enveloppe | `BASE_DE_DONNEES` | verrou retenu par un traitement concurrent (délai dépassé) |
| P2 | Taux de TVA (0,20) lu comme un pourcentage | `CODE_JAVA` | calcul faux du TTC (HT × 21), sans exception |
| P3 | Défaut T1a : confirmation de l'acheteur sans valorisation | `FLUX_METIER` | validée jusqu'au DG sans valorisation |
| T | Témoin sain : demande valorisée normalement, en attente AMG | `AUCUN` | aucune anomalie établie |

## 2. Préparation : état initial identique pour chaque série

À faire **avant chaque série**, à la racine du dépôt (Windows PowerShell). Efface toute la base
locale (traces, diagnostics, demandes) : c'est voulu, pour que les deux séries partent du même
état et accumulent le même historique.

```powershell
docker compose down -v
docker compose up -d --build
docker compose ps                                   # 4 services "healthy"
Invoke-RestMethod http://localhost:8000/health      # attendre documents_vectorises >= 12
git log -1 --format=%h                              # noter le commit ci-dessous
```

Commit de la série : `______`. Entre la préparation et le dernier essai : aucune modification
du code, de la configuration ni des données, et aucun test automatisé.

## 3. Déroulé : 18 essais, toujours dans l'ordre de la grille

```powershell
. .\docs\evaluation\scenarios.ps1      # une fois par fenêtre (point, espace, chemin)
$id = Invoke-PanneP1                    # selon la colonne "cas" : Invoke-PanneP1/P2/P3 ou Invoke-Temoin
Invoke-Diagnostic $id                   # affiche les valeurs à reporter
```

Si un scénario s'arrête sur une erreur (le simulateur n'a pas réagi comme prévu) : issue
`SCENARIO_INVALIDE`, pas de diagnostic, passer à l'essai suivant.

## 4. Remplir la grille (séparateur `;`)

| Colonne | Contenu |
|---|---|
| `horodatage`, `correlation_id`, `composant_obtenu`, `confiance`, `preuves`, `preuves_rejetees`, `incidents_proches`, `modele`, `latence_ms` | recopiés depuis l'affichage |
| `composant_correct_0_1` | 1 si le composant obtenu est l'attendu (témoin : toujours 0, aucun composant n'est juste) |
| `cause_juste_0_1` | **ton jugement** : la cause racine décrit-elle la cause attendue ? (témoin : 1 si la cause dit qu'aucune anomalie n'est établie) |
| `preuve_pertinente_0_1` | au moins une preuve citée porte réellement la panne (P1 : la trace en échec de verrou ; P2 : la valorisation ou une règle de calcul ; P3 : la confirmation de l'acheteur, l'état sans montant ou R-09) |
| `bascule_0_1` | 1 si `modele` est le modèle de secours (`gemini-3.5-flash-lite`) |
| `relance_0_1` | 1 si le diagnostic a été relancé après un échec technique |
| `issue` | une des 4 valeurs ci-dessous |

**Issues**

| Issue | Règle |
|---|---|
| `REUSSITE` | panne : composant correct **et** cause juste. Témoin : cause juste **ou** confiance < 0,5 |
| `MAUVAISE_REPONSE` | diagnostic abouti qui ne remplit pas la règle ci-dessus |
| `ECHEC_TECHNIQUE` | pas de diagnostic exploitable (502, 504…) après **une** relance au plus |
| `SCENARIO_INVALIDE` | le scénario n'a pas produit la panne attendue |

## 5. Indicateurs

Dénominateur unique : les essais d'issue `REUSSITE` ou `MAUVAISE_REPONSE` (diagnostics aboutis).

| Indicateur | Calcul |
|---|---|
| Réussite par cas | `REUSSITE` / essais aboutis du cas, écrit « x/5 » (témoin « x/3 ») |
| Réussite, modèle principal seul | même calcul sur les essais où `bascule_0_1` = 0 (indicateur principal) |
| Réussite, modèle de secours | même calcul sur `bascule_0_1` = 1, rapporté à part |
| Composant correct mais cause fausse | nombre d'essais `composant_correct_0_1` = 1 et `cause_juste_0_1` = 0 |
| Confiance moyenne | selon `issue`, pour voir si une mauvaise réponse est annoncée avec assurance |
| Latence médiane | séparément pour chaque modèle |
| Échecs techniques | nombre, sur 18 |

## 6. Ce qu'on peut écrire, et ce qu'on ne peut pas

- Écrire « x/5 par panne, n = 18 dont 3 témoins, pannes conçues par l'auteur : une tendance,
  pas une preuve statistique ». Pas de pourcentage, pas de « significatif ».
- Entre les deux mesures, seul un passage de 0/5 à 5/5 ou de 1/5 à 5/5 sur une même panne est
  un écart net ; toute autre différence peut venir du hasard.
- Les essais ne sont pas indépendants : chaque essai devient un « incident proche » pour les
  suivants. La remise à zéro rend cette accumulation identique dans les deux séries.

## 7. Limites connues

- **Non-déterminisme** : la température du modèle n'est pas fixée (valeur par défaut) ; deux
  diagnostics du même dossier peuvent différer.
- **Disponibilité de Gemini** : le modèle utilisé dépend de la charge de Google (bascule vers
  le secours après deux délais dépassés) ; d'où l'indicateur « modèle principal seul ».
- **Confiance** : déclarée par le modèle, sans calibration ; elle est mesurée, pas garantie.
- **Hypothèse attendue en mesure 1** : P2 non diagnostiquée (le dossier n'a ni HT, ni taux de
  TVA, et la règle de calcul R-05 n'est pas retenue parmi les 4 règles les plus proches).
