"""Tests d'aiops-engine.

- Tests unitaires : fonctions pures, sans base.
- Tests d'integration : executes DANS le reseau Docker Compose, contre la pile
  reelle (simulateur d'achats, PostgreSQL, aiops-engine en marche).
"""

import asyncio
import os

import asyncpg
import httpx
import pytest

from app.config import Parametres
from app.ingestion import copier_trace, lire_identifiant, rattraper

AIOPS_URL = os.environ.get("AIOPS_URL", "http://aiops-engine:8000")
ERP_URL = os.environ.get("ERP_URL", "http://erp-simule:8080")
TRACE_SOUMISSION = "DemandeAchatInterneService.soumettre:SUCCES"


# --------------------------------------------------------------------------- unitaires

@pytest.mark.parametrize(("charge", "attendu"), [
    ("42", 42),
    ("abc", None),
    ("", None),
    ("-3", None),
    ("0", None),
])
def test_lire_identifiant(charge: str, attendu: int | None) -> None:
    assert lire_identifiant(charge) == attendu


# --------------------------------------------------------------------------- integration

@pytest.fixture
async def pool() -> asyncpg.Pool:
    p = Parametres()
    connexions = await asyncpg.create_pool(
        host=p.db_host, port=p.db_port, database=p.db_name, user=p.db_user,
        password=p.db_password.get_secret_value(), min_size=1, max_size=2)
    yield connexions
    await connexions.close()


async def _creer_et_soumettre(pool: asyncpg.Pool, client: httpx.AsyncClient) -> str:
    """Cree une demande dans le simulateur d'achats et la soumet ; retourne son id."""
    demandeur = await pool.fetchval(
        "SELECT id FROM erp.utilisateur WHERE email = 'mehdi.demandeur@demo.local'")
    reponse = await client.post(
        f"{ERP_URL}/api/demandes",
        headers={"X-Utilisateur-Id": str(demandeur), "X-Request-Id": "pytest-j4"},
        json={"justification": "Test ingestion",
              "lignes": [{"designationLibre": "Clavier", "quantite": 1}]})
    assert reponse.status_code == 201, reponse.text
    identifiant = reponse.json()["id"]
    reponse = await client.post(f"{ERP_URL}/api/demandes/{identifiant}/soumission")
    assert reponse.status_code == 200, reponse.text
    return identifiant


async def test_sante_ecoute_active() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        reponse = await client.get(f"{AIOPS_URL}/health")
    assert reponse.status_code == 200
    assert reponse.json()["ecoute"] is True


async def test_trace_notifiee_puis_copiee_en_moins_de_5_s(pool: asyncpg.Pool) -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        identifiant = await _creer_et_soumettre(pool, client)
        operations: list[str] = []
        for _ in range(50):
            reponse = await client.get(f"{AIOPS_URL}/traces/{identifiant}")
            assert reponse.status_code == 200
            operations = [f"{t['composant']}.{t['operation']}:{t['statut']}" for t in reponse.json()]
            if TRACE_SOUMISSION in operations:
                break
            await asyncio.sleep(0.1)
    assert TRACE_SOUMISSION in operations


async def test_rattrapage_recopie_une_trace_manquante(pool: asyncpg.Pool) -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        await _creer_et_soumettre(pool, client)
    source_id = await pool.fetchval("SELECT max(id) FROM erp.trace_execution")
    # On simule une trace manquee pendant une absence d'aiops-engine.
    await pool.execute("DELETE FROM aiops.transaction_trace_event WHERE source_id = $1", source_id)

    copiees = await rattraper(pool)

    assert copiees >= 1
    assert await pool.fetchval(
        "SELECT count(*) FROM aiops.transaction_trace_event WHERE source_id = $1", source_id) == 1


async def test_copie_idempotente(pool: asyncpg.Pool) -> None:
    source_id = await pool.fetchval("SELECT max(id) FROM erp.trace_execution")
    await rattraper(pool)

    nouvelle = await copier_trace(pool, source_id)

    assert nouvelle is False
    assert await pool.fetchval(
        "SELECT count(*) FROM aiops.transaction_trace_event WHERE source_id = $1", source_id) == 1


async def test_correlation_invalide_refusee() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        reponse = await client.get(f"{AIOPS_URL}/traces/pas-un-uuid")
    assert reponse.status_code == 422
