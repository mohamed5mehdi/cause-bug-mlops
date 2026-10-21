"""Tests d'integration des pannes injectees P1 et P2 (ADR-018), contre la pile Docker
Compose reelle (simulateur d'achats lance avec le profil "pannes").

Chaque test arme sa panne puis la desarme dans un bloc finally : un echec de test ne
laisse jamais le simulateur dans un etat casse.
"""

import asyncio
from decimal import Decimal

import asyncpg
import httpx

from tests.test_ingestion import AIOPS_URL, ERP_URL, pool  # noqa: F401 (fixture)

PRIX_HT = 10  # petit montant : le budget de demo n'est pas epuise par des executions repetees


async def _ids(pool: asyncpg.Pool) -> dict[str, int]:  # noqa: F811
    return {
        "demandeur": await pool.fetchval("SELECT id FROM erp.utilisateur WHERE email = 'mehdi.demandeur@demo.local'"),
        "manager": await pool.fetchval("SELECT id FROM erp.utilisateur WHERE email = 'manager.n1@demo.local'"),
        "fournisseur": await pool.fetchval(
            "SELECT id FROM erp.fournisseur WHERE raison_sociale = 'Fournisseur Demo Informatique'"),
        "sous_famille": await pool.fetchval("SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-MAT'"),
    }


async def _jusqu_a_la_valorisation(client: httpx.AsyncClient, ids: dict[str, int]) -> httpx.Response:
    """Cree une demande, la fait valider (N+1, technique) puis la valorise a 20 % de TVA."""
    r = await client.post(f"{ERP_URL}/api/demandes", headers={"X-Utilisateur-Id": str(ids["demandeur"])},
                          json={"justification": "Test panne", "lignes": [
                              {"designationLibre": "Souris", "quantite": 1}]})
    assert r.status_code == 201, r.text
    demande = r.json()
    base = f"{ERP_URL}/api/demandes/{demande['id']}"
    assert (await client.post(f"{base}/soumission")).status_code == 200
    assert (await client.post(f"{base}/validation-n1", headers={"X-Utilisateur-Id": str(ids["manager"])},
                              json={"valider": True})).status_code == 200
    assert (await client.post(f"{base}/validation-technique")).status_code == 200
    return await client.post(f"{base}/valorisation", json={
        "tauxTva": 0.20, "fournisseurId": ids["fournisseur"], "sousFamilleId": ids["sous_famille"],
        "prix": [{"ligneId": demande["lignes"][0]["id"], "prixUnitaireHt": PRIX_HT}]})


async def _trace(client: httpx.AsyncClient, correlation_id: str, operation: str) -> dict[str, object]:
    """Attend la trace d'une operation dans aiops-engine (ingestion asynchrone)."""
    for _ in range(50):
        r = await client.get(f"{AIOPS_URL}/traces/{correlation_id}")
        for t in r.json():
            if f"{t['composant']}.{t['operation']}" == operation:
                return t
        await asyncio.sleep(0.2)
    raise AssertionError(f"Trace {operation} absente pour {correlation_id}")


async def test_pannes_desarmees_par_defaut_et_code_inconnu_refuse() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        etat = await client.get(f"{ERP_URL}/api/pannes")
        inconnu = await client.post(f"{ERP_URL}/api/pannes/P9")
    assert etat.status_code == 200
    assert {p["code"]: p["armee"] for p in etat.json()} == {"P1": False, "P2": False}
    assert {p["code"]: p["composantAttendu"] for p in etat.json()} == {"P1": "BASE_DE_DONNEES", "P2": "CODE_JAVA"}
    assert inconnu.status_code == 400


async def test_sans_panne_le_ttc_vaut_ht_fois_1_20(pool: asyncpg.Pool) -> None:  # noqa: F811
    ids = await _ids(pool)
    async with httpx.AsyncClient(timeout=30) as client:
        r = await _jusqu_a_la_valorisation(client, ids)
    assert r.status_code == 200, r.text
    assert Decimal(str(r.json()["montantTtc"])) == Decimal("12.00")


async def test_p2_le_ttc_vaut_21_fois_le_ht_sans_aucune_exception(pool: asyncpg.Pool) -> None:  # noqa: F811
    ids = await _ids(pool)
    async with httpx.AsyncClient(timeout=30) as client:
        try:
            assert (await client.post(f"{ERP_URL}/api/pannes/P2")).status_code == 200
            r = await _jusqu_a_la_valorisation(client, ids)
        finally:
            await client.delete(f"{ERP_URL}/api/pannes/P2")
        assert r.status_code == 200, r.text
        demande = r.json()
        # Panne silencieuse : la demande avance, mais engage 21 fois le HT au lieu de 1,20 fois.
        assert Decimal(str(demande["montantTtc"])) == Decimal(PRIX_HT * 21)
        assert Decimal(str(demande["tauxTva"])) == Decimal("0.20")
        trace = await _trace(client, demande["id"], "DemandeAchatInterneService.valoriser")
    assert trace["statut"] == "SUCCES"


async def test_p1_verrou_retenu_donne_503_et_une_trace_d_echec(pool: asyncpg.Pool) -> None:  # noqa: F811
    ids = await _ids(pool)
    async with httpx.AsyncClient(timeout=30) as client:
        try:
            assert (await client.post(f"{ERP_URL}/api/pannes/P1")).status_code == 200
            r = await _jusqu_a_la_valorisation(client, ids)
        finally:
            await client.delete(f"{ERP_URL}/api/pannes/P1")
        assert r.status_code == 503, r.text
        corps = r.json()
        assert corps["code"] == "RESSOURCE_VERROUILLEE"
        assert r.headers.get("Retry-After") == "1"
        trace = await _trace(client, corps["businessCorrelationId"], "BudgetGovernanceService.verifierEtEngager")
        # Laisse la cloture concurrente (6 s) relacher le verrou avant les tests suivants.
        await asyncio.sleep(6)
    assert trace["statut"] == "ECHEC"
    assert trace["duree_ms"] >= 2500, "l'echec doit survenir apres l'attente du verrou (3 s)"
    assert "Lock" in str(trace["exception_type"])
