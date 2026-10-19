"""Tests de la base de connaissance vectorielle (J5, ADR-012).

1. Unitaires : normalisation, formats de texte, sans reseau.
2. pgvector avec un FAUX modele d'embedding a geometrie connue : prouve que le
   stockage, l'index HNSW et la recherche par distance cosinus fonctionnent,
   sans appel a l'API Gemini (isole par le nom de modele "faux-modele-test").
3. Gemini reel : executes seulement si GEMINI_API_KEY est definie.
"""

import asyncio
import math
import os

import asyncpg
import httpx
import pytest

from app.config import Parametres
from app.connaissance import decrire_echec, rechercher_similaires, vectoriser_echecs_manquants
from app.embeddings import (
    DIMENSION,
    EmbeddingsGemini,
    ErreurEmbedding,
    en_litteral_pgvector,
    normaliser,
    texte_document,
    texte_requete,
)

AIOPS_URL = os.environ.get("AIOPS_URL", "http://aiops-engine:8000")
ERP_URL = os.environ.get("ERP_URL", "http://erp-simule:8080")
CLE = os.environ.get("GEMINI_API_KEY", "")
avec_gemini = pytest.mark.skipif(not CLE or CLE == "a-remplacer", reason="GEMINI_API_KEY absente")

FAUX_MODELE = "faux-modele-test"
ID_BASE = 900_000_000  # identifiants de test, hors de la plage reelle


def _cosinus(a: list[float], b: list[float]) -> float:
    return math.fsum(x * y for x, y in zip(a, b, strict=True))


# --------------------------------------------------------------------------- unitaires

def test_normaliser_donne_une_norme_de_1_et_garde_la_direction() -> None:
    v = normaliser([3.0, 4.0])
    assert v == pytest.approx([0.6, 0.8])
    assert math.sqrt(math.fsum(x * x for x in v)) == pytest.approx(1.0)


def test_normaliser_refuse_le_vecteur_nul() -> None:
    with pytest.raises(ErreurEmbedding):
        normaliser([0.0, 0.0, 0.0])


def test_formats_de_texte_attendus_par_gemini_embedding_2() -> None:
    assert texte_document("A.b", "Echec X") == "title: A.b | text: Echec X"
    assert texte_document("", "Echec X") == "title: none | text: Echec X"
    assert texte_requete("budget depasse") == "task: search result | query: budget depasse"


def test_decrire_echec_garde_le_nom_court_de_l_exception() -> None:
    titre, contenu = decrire_echec("BudgetGovernanceService", "verifierEtEngager",
                                   "com.x.BudgetInsuffisantException", "Budget insuffisant")
    assert titre == "BudgetGovernanceService.verifierEtEngager"
    assert contenu == "Echec BudgetInsuffisantException : Budget insuffisant"


def test_litteral_pgvector() -> None:
    assert en_litteral_pgvector([0.5, -1.0]) == "[0.5,-1.0]"


# --------------------------------------------------------------------------- pgvector (faux modele)

class FauxEmbeddings:
    """Geometrie connue : 'Budget' -> axe 0, 'Stock' -> axe 1, sinon axe 2."""

    nom_modele = FAUX_MODELE

    async def vectoriser(self, texte_prepare: str) -> list[float]:
        v = [0.0] * DIMENSION
        if "Budget" in texte_prepare or "budget" in texte_prepare:
            v[0] = 1.0
        elif "Stock" in texte_prepare or "stock" in texte_prepare:
            v[1] = 1.0
        else:
            v[2] = 1.0
        v[3] = 0.1  # petit bruit commun : evite des vecteurs strictement identiques
        return normaliser(v)


@pytest.fixture
async def pool() -> asyncpg.Pool:
    p = Parametres()
    connexions = await asyncpg.create_pool(
        host=p.db_host, port=p.db_port, database=p.db_name, user=p.db_user,
        password=p.db_password.get_secret_value(), min_size=1, max_size=2)
    yield connexions
    await connexions.execute("DELETE FROM aiops.document_vectorise WHERE modele = $1", FAUX_MODELE)
    # Le service en marche peut avoir vectorise une trace de test avec le vrai modele
    # pendant le test (meme base partagee) : on la retire aussi.
    await connexions.execute(
        "DELETE FROM aiops.document_vectorise WHERE type_document = 'TRACE_ECHEC' "
        "AND reference ~ '^[0-9]+$' AND reference::bigint >= $1", ID_BASE)
    await connexions.execute("DELETE FROM aiops.transaction_trace_event WHERE source_id >= $1", ID_BASE)
    await connexions.close()


async def _inserer_trace(pool: asyncpg.Pool, decalage: int, statut: str,
                         exception: str | None, message: str | None) -> None:
    await pool.execute(
        """INSERT INTO aiops.transaction_trace_event
               (source_id, debut, duree_ms, composant, operation, statut, exception_type, exception_message)
           VALUES ($1, now(), 1, 'ServiceTest', 'operationTest', $2, $3, $4)""",
        ID_BASE + decalage, statut, exception, message)


async def test_vectorisation_des_seuls_echecs_puis_recherche_par_le_sens(pool: asyncpg.Pool) -> None:
    await _inserer_trace(pool, 1, "ECHEC", "x.BudgetInsuffisantException", "Budget insuffisant")
    await _inserer_trace(pool, 2, "ECHEC", "x.StockInsuffisantException", "Stock insuffisant")
    await _inserer_trace(pool, 3, "SUCCES", None, None)
    faux = FauxEmbeddings()

    await vectoriser_echecs_manquants(pool, faux, limite=1000)
    references = {r["reference"] for r in await pool.fetch(
        "SELECT reference FROM aiops.document_vectorise WHERE modele = $1", FAUX_MODELE)}

    assert str(ID_BASE + 1) in references
    assert str(ID_BASE + 2) in references
    assert str(ID_BASE + 3) not in references  # un succes n'est jamais vectorise

    # D'autres echecs budget reels peuvent exister dans la base partagee : avec le faux
    # modele ils ont le meme vecteur. On verifie donc la propriete (le plus proche est un
    # echec budget, la trace de test en fait partie, le stock est plus loin), pas un rang.
    trouves = await rechercher_similaires(pool, faux, "depassement de budget", k=100)
    assert "Budget" in trouves[0].texte
    assert trouves[0].similarite > 0.99
    par_reference = {d.reference: d.similarite for d in trouves}
    assert par_reference[str(ID_BASE + 1)] > 0.99
    assert par_reference[str(ID_BASE + 2)] < par_reference[str(ID_BASE + 1)]


async def test_vectorisation_idempotente(pool: asyncpg.Pool) -> None:
    await _inserer_trace(pool, 4, "ECHEC", "x.BudgetInsuffisantException", "Budget insuffisant")
    faux = FauxEmbeddings()
    await vectoriser_echecs_manquants(pool, faux, limite=1000)

    seconde_passe = await vectoriser_echecs_manquants(pool, faux, limite=1000)

    assert seconde_passe == 0


async def test_la_recherche_peut_utiliser_l_index_hnsw(pool: asyncpg.Pool) -> None:
    """L'operateur de la requete (<=>) correspond a celui de l'index (vector_cosine_ops)."""
    requete = en_litteral_pgvector(normaliser([1.0] + [0.0] * (DIMENSION - 1)))
    async with pool.acquire() as connexion, connexion.transaction():
        await connexion.execute("SET LOCAL enable_seqscan = off")
        plan = "\n".join(r[0] for r in await connexion.fetch(
            "EXPLAIN SELECT id FROM aiops.document_vectorise "
            "ORDER BY embedding <=> $1::vector LIMIT 5", requete))
    assert "idx_document_vectorise_hnsw" in plan


# --------------------------------------------------------------------------- Gemini reel

@avec_gemini
async def test_gemini_dimension_768_norme_1_et_sens_coherent() -> None:
    gemini = EmbeddingsGemini(CLE, Parametres().modele_embedding)
    try:
        budget_1 = await gemini.vectoriser(texte_document(
            "BudgetGovernanceService.verifierEtEngager", "Echec BudgetInsuffisantException : Budget insuffisant"))
        budget_2 = await gemini.vectoriser(texte_requete(
            "l'enveloppe budgetaire ne couvre pas le montant de la commande"))
        sans_rapport = await gemini.vectoriser(texte_requete("recette de gateau au chocolat"))
    finally:
        await gemini.fermer()

    assert len(budget_1) == DIMENSION
    assert math.sqrt(math.fsum(x * x for x in budget_1)) == pytest.approx(1.0, abs=1e-6)
    assert _cosinus(budget_1, budget_2) > _cosinus(budget_1, sans_rapport)


@avec_gemini
async def test_bout_en_bout_un_echec_reel_est_retrouve_par_le_sens(pool: asyncpg.Pool) -> None:
    """Un 422 budget dans le simulateur -> trace -> copie -> vecteur -> retrouve par /recherche."""
    async with httpx.AsyncClient(timeout=20) as client:
        demandeur = await pool.fetchval(
            "SELECT id FROM erp.utilisateur WHERE email = 'mehdi.demandeur@demo.local'")
        manager = await pool.fetchval("SELECT id FROM erp.utilisateur WHERE email = 'manager.n1@demo.local'")
        fournisseur = await pool.fetchval(
            "SELECT id FROM erp.fournisseur WHERE raison_sociale = 'Fournisseur Demo Informatique'")
        sous_famille = await pool.fetchval(
            "SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-LOG'")
        r = await client.post(f"{ERP_URL}/api/demandes", headers={"X-Utilisateur-Id": str(demandeur)},
                              json={"justification": "Licences", "lignes": [
                                  {"designationLibre": "Suite logicielle", "quantite": 1}]})
        demande = r.json()
        base = f"{ERP_URL}/api/demandes/{demande['id']}"
        await client.post(f"{base}/soumission")
        await client.post(f"{base}/validation-n1", headers={"X-Utilisateur-Id": str(manager)},
                          json={"valider": True})
        await client.post(f"{base}/validation-technique")
        r = await client.post(f"{base}/valorisation", json={
            "tauxTva": 0.20, "fournisseurId": fournisseur, "sousFamilleId": sous_famille,
            "prix": [{"ligneId": demande["lignes"][0]["id"], "prixUnitaireHt": 1000000}]})
        assert r.status_code == 422

        trouves: list[dict] = []
        for _ in range(60):
            r = await client.get(f"{AIOPS_URL}/recherche",
                                 params={"q": "l'enveloppe budgetaire est insuffisante", "k": 3})
            trouves = r.json() if r.status_code == 200 else []
            if trouves and "BudgetInsuffisantException" in trouves[0]["texte"]:
                break
            await asyncio.sleep(0.5)

    assert trouves, "aucun document retrouve"
    assert trouves[0]["type_document"] == "TRACE_ECHEC"
    assert "BudgetInsuffisantException" in trouves[0]["texte"]
