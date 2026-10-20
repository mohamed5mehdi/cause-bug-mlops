"""Tests du diagnostic RAG (J6, ADR-013).

1. Unitaires : decoupage des regles, question de recherche, dossier d'enquete.
2. Garde-fous du generateur Gemini, avec un faux client (sans reseau) :
   reponse None ou hors bornes -> echec explicite.
3. Orchestration sur la vraie base avec faux modeles : preuves inventees retirees,
   diagnostic enregistre, dossier sans trace -> erreur dediee.
4. Gemini reel (si GEMINI_API_KEY) : un vrai refus budgetaire est diagnostique.
"""

import asyncio
import os
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID, uuid4

import asyncpg
import httpx
import pytest

from app.config import Parametres
from app.connaissance import DocumentTrouve, lire_regles
from app.diagnostic import (
    Composant,
    DiagnosticGemini,
    DossierSansTrace,
    ErreurDiagnostic,
    GenerateurGemini,
    construire_dossier,
    diagnostiquer,
    question_de_recherche,
)
from app.embeddings import DIMENSION, normaliser
from app.modeles import StatutTrace, TraceEvenement

AIOPS_URL = os.environ.get("AIOPS_URL", "http://aiops-engine:8000")
ERP_URL = os.environ.get("ERP_URL", "http://erp-simule:8080")
CLE = os.environ.get("GEMINI_API_KEY", "")
avec_gemini = pytest.mark.skipif(not CLE or CLE == "a-remplacer", reason="GEMINI_API_KEY absente")

ID_BASE = 910_000_000
FAUX_MODELE = "faux-modele-diagnostic"


def _trace(i: int, operation: str, statut: str, exception: str | None = None,
           message: str | None = None) -> TraceEvenement:
    return TraceEvenement(source_id=i, debut=datetime(2026, 10, 19, 10, 0, i, tzinfo=timezone.utc),
                          duree_ms=5, request_id="r", business_correlation_id=None,
                          composant="DemandeAchatInterneService", operation=operation,
                          statut=StatutTrace(statut), exception_type=exception, exception_message=message)


# --------------------------------------------------------------------------- unitaires

def test_le_corpus_contient_12_regles_numerotees() -> None:
    corpus = (Path(Parametres().fichier_regles)).read_text(encoding="utf-8")
    regles = lire_regles(corpus)
    assert [r.reference for r in regles] == [f"R-{i:02d}" for i in range(1, 13)]
    assert all(len(r.contenu) > 50 for r in regles)


def test_question_de_recherche_privilegie_les_echecs() -> None:
    traces = [_trace(1, "soumettre", "SUCCES"),
              _trace(2, "valoriser", "ECHEC", "x.BudgetInsuffisantException", "Budget insuffisant")]
    assert "BudgetInsuffisantException" in question_de_recherche(traces)


def test_question_de_recherche_sans_echec_decrit_la_sequence() -> None:
    traces = [_trace(1, "soumettre", "SUCCES"), _trace(2, "validerDg", "SUCCES")]
    assert question_de_recherche(traces) == "sequence d'etapes : soumettre, validerDg"


def test_dossier_numerote_et_references_valides() -> None:
    traces = [_trace(1, "soumettre", "SUCCES"), _trace(2, "valoriser", "ECHEC", "x.E", "boom")]
    incidents = [DocumentTrouve("TRACE_ECHEC", "42", "ancien echec", 0.8)]
    regles = [DocumentTrouve("REGLE_METIER", "R-06", "R-06 Gouvernance | ...", 0.7)]
    dossier, references = construire_dossier(traces, None, [], incidents, regles)
    assert references == {"T1", "T2", "I1", "R-06"}
    assert "[T2]" in dossier and "-> E : boom" in dossier and "[R-06]" in dossier


# --------------------------------------------------------------------------- garde-fous Gemini

def _generateur_avec_reponse(reponse: object) -> GenerateurGemini:
    generateur = GenerateurGemini("cle-de-test-sans-reseau", "modele-test")

    async def generate_content(**_kwargs: object) -> object:
        return reponse

    generateur._client = SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    return generateur


async def test_reponse_none_est_un_echec_explicite() -> None:
    generateur = _generateur_avec_reponse(SimpleNamespace(
        parsed=None, candidates=[SimpleNamespace(finish_reason="MAX_TOKENS")], usage_metadata=None))
    with pytest.raises(ErreurDiagnostic, match="non conforme"):
        await generateur.generer("consigne", "dossier")


def _erreur_api(code: int) -> Exception:
    """Erreur de l'API Gemini avec un code donne (sans dependre du constructeur du SDK)."""
    from google.genai import errors
    erreur = errors.APIError.__new__(errors.APIError)
    erreur.code = code
    return erreur


async def test_erreur_passagere_puis_bascule_vers_le_modele_de_secours() -> None:
    generateur = GenerateurGemini("cle-de-test-sans-reseau", "principal", "secours",
                                  tentatives=2, attente_initiale_s=0.0)
    appels: list[str] = []
    valide = DiagnosticGemini(cause_racine="ok", confiance=0.5, composant=Composant.FLUX_METIER, preuves=[])

    async def generate_content(model: str, **_kwargs: object) -> object:
        appels.append(model)
        if model == "principal":
            raise _erreur_api(503)
        return SimpleNamespace(parsed=valide, candidates=[], usage_metadata=None)

    generateur._client = SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    resultat, infos = await generateur.generer("consigne", "dossier")

    assert appels == ["principal", "principal", "secours"]
    assert infos["modele"] == "secours"
    assert resultat.cause_racine == "ok"


async def test_delai_depasse_retente_puis_bascule_vers_le_secours() -> None:
    generateur = GenerateurGemini("cle-de-test-sans-reseau", "principal", "secours",
                                  tentatives=2, attente_initiale_s=0.0, delai_s=5)
    # Le SDK attend des millisecondes : 5 s -> 5000.
    assert generateur._client._api_client._http_options.timeout == 5000
    appels: list[str] = []
    valide = DiagnosticGemini(cause_racine="ok", confiance=0.5, composant=Composant.FLUX_METIER, preuves=[])

    async def generate_content(model: str, **_kwargs: object) -> object:
        appels.append(model)
        if model == "principal":
            raise httpx.ReadTimeout("delai depasse")
        return SimpleNamespace(parsed=valide, candidates=[], usage_metadata=None)

    generateur._client = SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    _, infos = await generateur.generer("consigne", "dossier")

    assert appels == ["principal", "principal", "secours"]
    assert infos["modele"] == "secours"


async def test_delai_depasse_cote_serveur_504_bascule_vers_le_secours() -> None:
    generateur = GenerateurGemini("cle-de-test-sans-reseau", "principal", "secours",
                                  tentatives=2, attente_initiale_s=0.0)
    appels: list[str] = []
    valide = DiagnosticGemini(cause_racine="ok", confiance=0.5, composant=Composant.FLUX_METIER, preuves=[])

    async def generate_content(model: str, **_kwargs: object) -> object:
        appels.append(model)
        if model == "principal":
            raise _erreur_api(504)
        return SimpleNamespace(parsed=valide, candidates=[], usage_metadata=None)

    generateur._client = SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    _, infos = await generateur.generer("consigne", "dossier")

    assert appels == ["principal", "principal", "secours"]
    assert infos["modele"] == "secours"


async def test_delai_depasse_partout_est_un_echec_explicite() -> None:
    generateur = GenerateurGemini("cle-de-test-sans-reseau", "principal", "secours",
                                  tentatives=2, attente_initiale_s=0.0, delai_s=5)

    async def generate_content(model: str, **_kwargs: object) -> object:
        raise httpx.ReadTimeout("delai depasse")

    generateur._client = SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    with pytest.raises(ErreurDiagnostic, match="delai de 5 s depasse"):
        await generateur.generer("consigne", "dossier")


async def test_erreur_definitive_sans_nouvelle_tentative() -> None:
    generateur = GenerateurGemini("cle-de-test-sans-reseau", "principal", "secours",
                                  tentatives=3, attente_initiale_s=0.0)
    appels: list[str] = []

    async def generate_content(model: str, **_kwargs: object) -> object:
        appels.append(model)
        raise _erreur_api(400)

    generateur._client = SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))
    with pytest.raises(ErreurDiagnostic, match="code 400"):
        await generateur.generer("consigne", "dossier")
    assert appels == ["principal"]


async def test_confiance_hors_bornes_est_refusee() -> None:
    hors_bornes = DiagnosticGemini.model_construct(cause_racine="x", confiance=1.7,
                                                   composant=Composant.CODE_JAVA, preuves=[])
    generateur = _generateur_avec_reponse(SimpleNamespace(parsed=hors_bornes, candidates=[],
                                                          usage_metadata=None))
    with pytest.raises(ErreurDiagnostic, match="hors bornes"):
        await generateur.generer("consigne", "dossier")


# --------------------------------------------------------------------------- orchestration (vraie base)

class FauxEmbeddings:
    nom_modele = FAUX_MODELE

    async def vectoriser(self, texte_prepare: str) -> list[float]:
        v = [0.0] * DIMENSION
        v[0] = 1.0
        return normaliser(v)


class FauxGenerateur:
    def __init__(self) -> None:
        self.dossier_recu = ""

    async def generer(self, consigne: str, dossier: str) -> tuple[DiagnosticGemini, dict[str, object]]:
        self.dossier_recu = dossier
        return DiagnosticGemini(cause_racine="Enveloppe budgetaire insuffisante", confiance=0.9,
                                composant=Composant.FLUX_METIER, preuves=["T1", "R-99"]), \
            {"modele": "faux-generateur", "entree": 100, "sortie": 20}


@pytest.fixture
async def pool() -> asyncpg.Pool:
    p = Parametres()
    connexions = await asyncpg.create_pool(
        host=p.db_host, port=p.db_port, database=p.db_name, user=p.db_user,
        password=p.db_password.get_secret_value(), min_size=1, max_size=2)
    yield connexions
    await connexions.execute("DELETE FROM aiops.diagnostic WHERE modele = 'faux-generateur'")
    await connexions.execute("DELETE FROM aiops.transaction_trace_event WHERE source_id >= $1", ID_BASE)
    await connexions.close()


async def test_preuves_inventees_retirees_et_diagnostic_enregistre(pool: asyncpg.Pool) -> None:
    correlation = uuid4()
    await pool.execute(
        """INSERT INTO aiops.transaction_trace_event (source_id, debut, duree_ms, business_correlation_id,
               composant, operation, statut, exception_type, exception_message)
           VALUES ($1, now(), 3, $2, 'BudgetGovernanceService', 'verifierEtEngager', 'ECHEC',
                   'x.BudgetInsuffisantException', 'Budget insuffisant')""", ID_BASE + 1, correlation)
    generateur = FauxGenerateur()

    d = await diagnostiquer(pool, FauxEmbeddings(), generateur, correlation)

    assert d.preuves == ["T1"]
    assert d.preuves_rejetees == ["R-99"]
    assert "[T1]" in generateur.dossier_recu and "BudgetInsuffisantException" in generateur.dossier_recu
    ligne = await pool.fetchrow("SELECT composant, preuves, preuves_rejetees FROM aiops.diagnostic "
                                "WHERE business_correlation_id = $1", correlation)
    assert ligne["composant"] == "FLUX_METIER"
    assert ligne["preuves"] == ["T1"]
    assert ligne["preuves_rejetees"] == 1


async def test_dossier_sans_trace(pool: asyncpg.Pool) -> None:
    with pytest.raises(DossierSansTrace):
        await diagnostiquer(pool, FauxEmbeddings(), FauxGenerateur(), uuid4())


# --------------------------------------------------------------------------- Gemini reel

@avec_gemini
async def test_bout_en_bout_un_refus_budgetaire_est_diagnostique(pool: asyncpg.Pool) -> None:
    # Les 12 regles doivent etre vectorisees (chargees en tache de fond au demarrage).
    for _ in range(60):
        n = await pool.fetchval("SELECT count(*) FROM aiops.document_vectorise "
                                "WHERE type_document = 'REGLE_METIER' AND modele = $1",
                                Parametres().modele_embedding)
        if n == 12:
            break
        await asyncio.sleep(0.5)
    assert n == 12

    # Au-dela du budget serveur (130 s, ADR-015) : le serveur repond toujours avant le client.
    async with httpx.AsyncClient(timeout=140) as client:
        demandeur = await pool.fetchval("SELECT id FROM erp.utilisateur WHERE email = 'mehdi.demandeur@demo.local'")
        manager = await pool.fetchval("SELECT id FROM erp.utilisateur WHERE email = 'manager.n1@demo.local'")
        fournisseur = await pool.fetchval(
            "SELECT id FROM erp.fournisseur WHERE raison_sociale = 'Fournisseur Demo Informatique'")
        sous_famille = await pool.fetchval("SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-LOG'")
        r = await client.post(f"{ERP_URL}/api/demandes", headers={"X-Utilisateur-Id": str(demandeur)},
                              json={"justification": "Serveurs", "lignes": [
                                  {"designationLibre": "Serveur de calcul", "quantite": 2}]})
        demande = r.json()
        base = f"{ERP_URL}/api/demandes/{demande['id']}"
        await client.post(f"{base}/soumission")
        await client.post(f"{base}/validation-n1", headers={"X-Utilisateur-Id": str(manager)}, json={"valider": True})
        await client.post(f"{base}/validation-technique")
        r = await client.post(f"{base}/valorisation", json={
            "tauxTva": 0.20, "fournisseurId": fournisseur, "sousFamilleId": sous_famille,
            "prix": [{"ligneId": demande["lignes"][0]["id"], "prixUnitaireHt": 900000}]})
        assert r.status_code == 422
        await asyncio.sleep(1.0)  # laisser la trace d'echec arriver dans aiops-engine

        r = await client.post(f"{AIOPS_URL}/diagnose/{demande['id']}")

    assert r.status_code == 200, r.text
    corps = r.json()
    print("\nDIAGNOSTIC :", corps)
    assert UUID(corps["business_correlation_id"]) == UUID(demande["id"])
    assert 0.0 <= corps["confiance"] <= 1.0
    assert corps["composant"] in {"CODE_JAVA", "BASE_DE_DONNEES", "FLUX_METIER"}
    assert "budg" in corps["cause_racine"].lower()
    assert corps["preuves"], "le diagnostic doit citer au moins une preuve du dossier"
    assert corps["contexte"]["regles_metier"] > 0
