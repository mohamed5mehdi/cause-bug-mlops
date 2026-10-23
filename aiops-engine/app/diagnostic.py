"""Diagnostic de cause racine par RAG (ADR-013).

1. Recuperer (Retrieval) : chronologie du dossier, etat et piste d'audit,
   incidents passes proches, regles metier proches (pgvector).
2. Augmenter : assembler ces elements dans un "dossier d'enquete" numerote.
3. Generer : Gemini (generate_content) avec un schema JSON impose (response_schema).

Garde-fous, conformes a la documentation Gemini et au SDK google-genai :
- response.parsed peut valoir None (sortie non conforme, coupee...) : echec explicite ;
- les bornes (confiance dans [0;1]) sont revalidees cote application ;
- les preuves citees doivent exister dans le dossier fourni : une reference
  inventee est retiree et comptee (signal d'hallucination).
"""

import asyncio
import logging
import time
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol
from uuid import UUID

import asyncpg
import httpx
from google import genai
from google.genai import errors, types
from pydantic import BaseModel, Field, ValidationError

from app.connaissance import (
    TYPE_REGLE_METIER,
    TYPE_TRACE_ECHEC,
    DocumentTrouve,
    rechercher_similaires,
)
from app.embeddings import ErreurEmbedding, FournisseurEmbeddings
from app.modeles import TraceEvenement
from app.traces import lire_chronologie

LOG = logging.getLogger(__name__)


class Composant(StrEnum):
    CODE_JAVA = "CODE_JAVA"
    BASE_DE_DONNEES = "BASE_DE_DONNEES"
    FLUX_METIER = "FLUX_METIER"


class DiagnosticGemini(BaseModel):
    """Schema impose a Gemini (response_schema). Les descriptions guident le modele."""

    cause_racine: str = Field(description="Cause racine en une ou deux phrases, en francais, "
                                          "factuelle, appuyee sur le dossier.")
    confiance: float = Field(ge=0.0, le=1.0, description="Confiance entre 0 et 1. Basse si le "
                                                          "dossier est incomplet ou ambigu.")
    composant: Composant = Field(description="Composant en cause.")
    preuves: list[str] = Field(description="References du dossier qui justifient le diagnostic, "
                                           "par exemple T3 pour une trace ou R-06 pour une regle.")


class ErreurDiagnostic(Exception):
    """Le diagnostic n'a pas pu etre produit (API indisponible, reponse non conforme)."""


class DossierSansTrace(Exception):
    """Aucune trace d'execution pour ce dossier."""


class GenerateurDiagnostic(Protocol):
    """Contrat du modele de langage : permet un faux en test.
    generer() retourne le diagnostic et des informations d'appel :
    {"modele": modele reellement utilise, "entree"/"sortie": jetons consommes}."""

    async def generer(self, consigne: str, dossier: str) -> tuple[DiagnosticGemini, dict[str, object]]: ...


# Erreurs passageres cote API : surcharge (503), quota (429), erreur interne (500), delai
# depasse cote serveur (504) : le SDK transmet notre delai dans l'en-tete X-Server-Timeout
# et Google coupe lui-meme la generation a cette echeance (observe le 2026-10-20).
CODES_PASSAGERS = {429, 500, 503, 504}


CONSIGNE_SYSTEME = """Tu es un analyste AIOps qui diagnostique la cause racine d'un incident \
dans un simulateur de processus d'achats (workflow de validation hierarchique avec \
gouvernance budgetaire).

Regles de travail :
- Appuie-toi UNIQUEMENT sur le dossier d'enquete fourni. N'invente aucun fait.
- Compare ce que montrent les traces et la piste d'audit avec les regles metier : un \
ecart (etape sautee, ordre anormal, montant incoherent) est un indice, meme sans exception.
- Cite dans "preuves" les references exactes du dossier (T1, T2... pour les traces, \
I1, I2... pour les incidents, R-01, R-02... pour les regles).
- Choisis le composant :
  CODE_JAVA : erreur technique inattendue dans le code (exception interne, calcul faux, \
etat incoherent produit par le programme) ;
  BASE_DE_DONNEES : verrou, delai d'attente, contrainte, connexion ou donnee incoherente \
en base ;
  FLUX_METIER : enchainement d'etapes non conforme aux regles metier, ou refus metier \
prevu par une regle (par exemple budget insuffisant).
- Arbitrage CODE_JAVA / FLUX_METIER : verifie que montant TTC = montant HT x (1 + taux TVA). \
Un montant calcule faux par le programme releve de CODE_JAVA, meme sans exception et meme \
si les etapes du flux sont conformes. FLUX_METIER suppose une etape sautee, contournee ou \
dans le mauvais ordre, visible dans les traces ou la piste d'audit.
- Un dossier peut etre sain : une demande en cours, dont les etapes deja franchies \
respectent les regles, n'est pas une anomalie. Si rien dans le dossier ne contredit les \
regles, ecris "Aucune anomalie etablie" dans cause_racine, avec une confiance inferieure \
a 0,5, et choisis le composant le moins improbable.
- Donne une confiance basse si le dossier ne permet pas de conclure."""


class GenerateurGemini:
    """Implementation reelle : generate_content avec schema JSON impose.

    Resilience : sur une erreur passagere (429, 500, 503), nouvelles tentatives avec
    attente croissante, puis bascule vers le modele de secours. Une erreur definitive
    (cle invalide, modele inconnu, requete refusee) echoue immediatement."""

    def __init__(self, cle_api: str, modele: str, modele_secours: str | None = None,
                 tentatives: int = 2, attente_initiale_s: float = 1.0, delai_s: float = 30.0) -> None:
        self.modeles = [modele] + ([modele_secours] if modele_secours and modele_secours != modele else [])
        self._tentatives = tentatives
        self._attente_initiale_s = attente_initiale_s
        self._delai_s = delai_s
        # Delai par appel HTTP (ADR-015). Le SDK l'attend en MILLISECONDES ; il ne fait
        # aucune nouvelle tentative de lui-meme (retry_options absent), donc pas de cumul.
        self._client = genai.Client(api_key=cle_api, http_options=types.HttpOptions(
            timeout=int(delai_s * 1000))).aio

    async def generer(self, consigne: str, dossier: str) -> tuple[DiagnosticGemini, dict[str, object]]:
        derniere = "?"
        for modele in self.modeles:
            for tentative in range(1, self._tentatives + 1):
                try:
                    resultat, jetons = await self._appeler(modele, consigne, dossier)
                    return resultat, {"modele": modele, **jetons}
                except errors.APIError as erreur:
                    if erreur.code not in CODES_PASSAGERS:
                        raise ErreurDiagnostic(f"API Gemini : code {erreur.code}") from erreur
                    derniere = f"code {erreur.code}"
                except httpx.TimeoutException:
                    # Un delai depasse leve une exception httpx (pas une APIError) :
                    # traite comme une surcharge passagere (nouvelle tentative, puis secours).
                    derniere = f"delai de {self._delai_s:.0f} s depasse"
                LOG.warning("Gemini %s : %s (tentative %d/%d)", modele, derniere,
                            tentative, self._tentatives)
                if tentative < self._tentatives:
                    await asyncio.sleep(self._attente_initiale_s * 2 ** (tentative - 1))
            LOG.warning("Modele %s indisponible, bascule vers le suivant", modele)
        raise ErreurDiagnostic(f"API Gemini indisponible : {derniere}")

    async def _appeler(self, modele: str, consigne: str, dossier: str) -> tuple[DiagnosticGemini, dict[str, int | None]]:
        reponse = await self._client.models.generate_content(
            model=modele,
            contents=dossier,
            config=types.GenerateContentConfig(
                system_instruction=consigne,
                response_mime_type="application/json",
                response_schema=DiagnosticGemini,
            ),
        )
        if reponse.parsed is None:
            fin = reponse.candidates[0].finish_reason if reponse.candidates else None
            LOG.warning("Reponse Gemini non conforme au schema (finish_reason=%s)", fin)
            raise ErreurDiagnostic(f"Reponse non conforme au schema (finish_reason={fin})")
        usage = reponse.usage_metadata
        jetons = {
            "entree": usage.prompt_token_count if usage else None,
            "sortie": usage.candidates_token_count if usage else None,
        }
        try:
            # Le SDK n'applique pas les validateurs Pydantic : on revalide ici (bornes, enum).
            resultat = DiagnosticGemini.model_validate(reponse.parsed.model_dump())
        except ValidationError as erreur:
            raise ErreurDiagnostic(f"Reponse hors bornes : {erreur.error_count()} erreur(s)") from erreur
        return resultat, jetons

    async def fermer(self) -> None:
        await self._client.aclose()


@dataclass(frozen=True)
class Diagnostic:
    business_correlation_id: UUID
    cause_racine: str
    confiance: float
    composant: Composant
    preuves: list[str]
    preuves_rejetees: list[str]
    modele: str
    latence_ms: int
    nb_traces: int
    nb_incidents: int
    nb_regles: int


ETAT_DOSSIER = """
    SELECT statut, type_demande, montant_ht, taux_tva, montant_ttc, fournisseur_selectionne_id
    FROM erp.demande_achat_interne WHERE id = $1
"""


def _valeur(v: object) -> str:
    """Valeur lisible pour le modele : une donnee absente est dite explicitement (ADR-021)."""
    return "non valorise" if v is None else str(v)


PISTE_AUDIT = """
    SELECT etape, decision, horodatage FROM erp.historique_validation
    WHERE business_correlation_id = $1 ORDER BY horodatage, id
"""

ENREGISTREMENT = """
    INSERT INTO aiops.diagnostic (business_correlation_id, cause_racine, confiance, composant,
        preuves, preuves_rejetees, modele, latence_ms, jetons_entree, jetons_sortie)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
"""


def question_de_recherche(traces: list[TraceEvenement]) -> str:
    """Ce que l'on cherche dans la base de connaissance : les echecs du dossier s'il y en
    a, sinon la sequence d'etapes (cas d'un ecart de flux sans exception)."""
    echecs = [t for t in traces if t.statut == "ECHEC"]
    if echecs:
        return " ; ".join(f"{t.composant}.{t.operation} echec {(t.exception_type or '').rsplit('.', 1)[-1]} "
                          f"{t.exception_message or ''}" for t in echecs)
    return "sequence d'etapes : " + ", ".join(t.operation for t in traces)


def construire_dossier(traces: list[TraceEvenement], etat: asyncpg.Record | None,
                       audit: list[asyncpg.Record], incidents: list[DocumentTrouve],
                       regles: list[DocumentTrouve]) -> tuple[str, set[str]]:
    """Assemble le dossier d'enquete numerote ; retourne aussi les references valides."""
    references: set[str] = set()
    lignes = ["# Dossier d'enquete", "", "## Chronologie technique du dossier (traces)"]
    for i, t in enumerate(traces, start=1):
        ref = f"T{i}"
        references.add(ref)
        detail = f" -> {t.exception_type.rsplit('.', 1)[-1]} : {t.exception_message}" if t.exception_type else ""
        lignes.append(f"[{ref}] {t.debut.isoformat()} {t.composant}.{t.operation} {t.statut} "
                      f"({t.duree_ms} ms){detail}")
    lignes += ["", "## Etat actuel de la demande"]
    if etat is None:
        lignes.append("Demande introuvable dans le systeme observe.")
    else:
        lignes.append(f"Statut : {etat['statut']} ; type : {etat['type_demande']} ; "
                      f"montant HT : {_valeur(etat['montant_ht'])} ; "
                      f"taux TVA : {_valeur(etat['taux_tva'])} ; "
                      f"montant TTC : {_valeur(etat['montant_ttc'])} ; "
                      f"fournisseur selectionne : {_valeur(etat['fournisseur_selectionne_id'])}")
    lignes += ["", "## Piste d'audit fonctionnelle (decisions de validation)"]
    lignes += [f"- {a['horodatage'].isoformat()} {a['etape']} : {a['decision']}" for a in audit] or [
        "Aucune decision enregistree."]
    lignes += ["", "## Incidents passes similaires"]
    for i, d in enumerate(incidents, start=1):
        ref = f"I{i}"
        references.add(ref)
        lignes.append(f"[{ref}] (similarite {d.similarite:.2f}) {d.texte}")
    if not incidents:
        lignes.append("Aucun incident similaire connu.")
    lignes += ["", "## Regles metier pertinentes"]
    for d in regles:
        references.add(d.reference)
        lignes.append(f"[{d.reference}] {d.texte}")
    return "\n".join(lignes), references


async def diagnostiquer(pool: asyncpg.Pool, embeddings: FournisseurEmbeddings,
                        generateur: GenerateurDiagnostic, correlation_id: UUID,
                        k_incidents: int = 3, k_regles: int = 12) -> Diagnostic:
    # k_regles = 12 : la base de connaissance compte 12 regles, toutes envoyees (ADR-021) ;
    # avec 4, la regle de calcul R-05 etait exclue du dossier P2 (mesure 1).
    debut = time.perf_counter()
    traces = await lire_chronologie(pool, correlation_id)
    if not traces:
        raise DossierSansTrace(str(correlation_id))
    etat = await pool.fetchrow(ETAT_DOSSIER, correlation_id)
    audit = await pool.fetch(PISTE_AUDIT, correlation_id)

    question = question_de_recherche(traces)
    propres = [str(t.source_id) for t in traces]  # ne pas se citer soi-meme comme "incident passe"
    try:
        incidents = await rechercher_similaires(pool, embeddings, question, k_incidents,
                                                [TYPE_TRACE_ECHEC], propres)
        regles = await rechercher_similaires(pool, embeddings, question, k_regles, [TYPE_REGLE_METIER])
    except ErreurEmbedding as erreur:
        raise ErreurDiagnostic(f"Recherche de contexte impossible : {erreur}") from erreur

    dossier, references = construire_dossier(traces, etat, audit, incidents, regles)
    resultat, infos = await generateur.generer(CONSIGNE_SYSTEME, dossier)
    modele = str(infos.get("modele"))

    preuves = [p for p in resultat.preuves if p in references]
    rejetees = [p for p in resultat.preuves if p not in references]
    if rejetees:
        LOG.warning("Preuves inventees retirees pour %s : %s", correlation_id, rejetees)
    latence_ms = int((time.perf_counter() - debut) * 1000)

    await pool.execute(ENREGISTREMENT, correlation_id, resultat.cause_racine, resultat.confiance,
                       resultat.composant.value, preuves, len(rejetees), modele,
                       latence_ms, infos.get("entree"), infos.get("sortie"))
    LOG.info("Diagnostic %s : %s (confiance %.2f) par %s en %d ms, jetons entree=%s sortie=%s",
             correlation_id, resultat.composant.value, resultat.confiance, modele, latence_ms,
             infos.get("entree"), infos.get("sortie"))
    return Diagnostic(correlation_id, resultat.cause_racine, resultat.confiance, resultat.composant,
                      preuves, rejetees, modele, latence_ms,
                      len(traces), len(incidents), len(regles))
