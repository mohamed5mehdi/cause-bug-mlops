"""Tests d'integration des lectures du tableau de bord (J7a) :
GET /dossiers et GET /diagnostics/{id}, contre la pile Docker Compose reelle."""

import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import asyncpg
import httpx

from tests.test_ingestion import AIOPS_URL, _creer_et_soumettre, pool  # noqa: F401 (fixture)


async def _attendre_dossier(client: httpx.AsyncClient, identifiant: str) -> dict[str, object]:
    """Attend que l'ingestion asynchrone ait copie au moins une trace du dossier."""
    for _ in range(50):
        reponse = await client.get(f"{AIOPS_URL}/dossiers", params={"limite": 200})
        assert reponse.status_code == 200
        for dossier in reponse.json():
            if dossier["business_correlation_id"] == identifiant:
                return dossier
        await asyncio.sleep(0.1)
    raise AssertionError(f"Dossier {identifiant} absent de /dossiers")


async def test_dossier_soumis_apparait_sans_echec(pool: asyncpg.Pool) -> None:  # noqa: F811
    async with httpx.AsyncClient(timeout=10) as client:
        identifiant = await _creer_et_soumettre(pool, client)
        dossier = await _attendre_dossier(client, identifiant)
    assert dossier["nb_traces"] >= 1
    assert dossier["nb_echecs"] == 0
    assert datetime.fromisoformat(str(dossier["premiere_trace"])) <= \
        datetime.fromisoformat(str(dossier["derniere_trace"]))


async def test_dossiers_tries_du_plus_recent_sans_identifiant_nul() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        reponse = await client.get(f"{AIOPS_URL}/dossiers")
    assert reponse.status_code == 200
    dossiers = reponse.json()
    assert all(d["business_correlation_id"] for d in dossiers)
    dates = [datetime.fromisoformat(d["derniere_trace"]) for d in dossiers]
    assert dates == sorted(dates, reverse=True)


async def test_limite_respectee_et_bornee() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        une = await client.get(f"{AIOPS_URL}/dossiers", params={"limite": 1})
        zero = await client.get(f"{AIOPS_URL}/dossiers", params={"limite": 0})
        trop = await client.get(f"{AIOPS_URL}/dossiers", params={"limite": 201})
    assert une.status_code == 200 and len(une.json()) <= 1
    assert zero.status_code == 422
    assert trop.status_code == 422


async def test_diagnostics_relus_du_plus_recent(pool: asyncpg.Pool) -> None:  # noqa: F811
    identifiant = uuid4()
    insertion = """
        INSERT INTO aiops.diagnostic (business_correlation_id, cause_racine, confiance, composant,
            preuves, preuves_rejetees, modele, latence_ms, jetons_entree, jetons_sortie, cree_le)
        VALUES ($1, $2, $3, 'FLUX_METIER', $4, 1, 'modele-test', 100, 10, 5, $5)
    """
    try:
        await pool.execute(insertion, identifiant, "ancien", 0.5, ["T1"], datetime(2026, 1, 1, tzinfo=UTC))
        await pool.execute(insertion, identifiant, "recent", 0.875, ["T2", "R-06"],
                           datetime(2026, 1, 2, tzinfo=UTC))
        async with httpx.AsyncClient(timeout=10) as client:
            reponse = await client.get(f"{AIOPS_URL}/diagnostics/{identifiant}")
    finally:
        await pool.execute("DELETE FROM aiops.diagnostic WHERE business_correlation_id = $1", identifiant)
    assert reponse.status_code == 200
    corps = reponse.json()
    assert [d["cause_racine"] for d in corps] == ["recent", "ancien"]
    assert corps[0]["confiance"] == 0.875
    assert corps[0]["preuves"] == ["T2", "R-06"]
    assert corps[0]["nb_preuves_rejetees"] == 1


async def test_diagnostics_dossier_inconnu_liste_vide() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        reponse = await client.get(f"{AIOPS_URL}/diagnostics/{uuid4()}")
    assert reponse.status_code == 200
    assert reponse.json() == []
