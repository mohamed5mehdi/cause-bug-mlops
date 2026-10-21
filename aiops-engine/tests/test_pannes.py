"""Tests d'integration des pannes P1 et P2 (injectees, ADR-018) et P3 (defaut T1a rejoue
par HTTP, ADR-019), contre la pile Docker Compose reelle (simulateur d'achats lance avec
le profil "pannes").

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


# --------------------------------------------------------------------------- P3 (ADR-019)

async def _ids_p3(pool: asyncpg.Pool) -> dict[str, int]:  # noqa: F811
    ids = await _ids(pool)
    for role, email in (("acheteur", "acheteur@demo.local"), ("amg", "amg@demo.local"), ("daf", "daf@demo.local")):
        ids[role] = await pool.fetchval("SELECT id FROM erp.utilisateur WHERE email = $1", email)
    ids["acc"] = await pool.fetchval("SELECT id FROM erp.sous_famille_budgetaire WHERE code = 'INFO-ACC'")
    return ids


async def _statut(client: httpx.AsyncClient, demande_id: str) -> dict[str, object]:
    return (await client.get(f"{ERP_URL}/api/demandes/{demande_id}")).json()


async def test_p3_demande_validee_jusqu_au_dg_sans_valorisation_puis_po_en_echec(
        pool: asyncpg.Pool) -> None:  # noqa: F811
    ids = await _ids_p3(pool)
    # Prix juste au-dessus du disponible de INFO-ACC : le scenario reste rejouable, chaque
    # passage ne transfere que le montant manquant (quelques unites).
    disponible = Decimal(str(await pool.fetchval(
        "SELECT montant_alloue - montant_engage FROM erp.sous_famille_budgetaire WHERE id = $1", ids["acc"])))
    prix_ht = int(disponible / Decimal("1.2")) + 10
    entete = lambda role: {"X-Utilisateur-Id": str(ids[role])}  # noqa: E731

    async with httpx.AsyncClient(timeout=30) as client:
        r = await client.post(f"{ERP_URL}/api/demandes", headers=entete("demandeur"),
                              json={"justification": "Scenario P3", "lignes": [
                                  {"designationLibre": "Station d'accueil", "quantite": 1}]})
        assert r.status_code == 201, r.text
        demande = r.json()
        base = f"{ERP_URL}/api/demandes/{demande['id']}"
        assert (await client.post(f"{base}/soumission")).status_code == 200
        assert (await client.post(f"{base}/validation-n1", headers=entete("manager"),
                                  json={"valider": True})).status_code == 200
        assert (await client.post(f"{base}/validation-technique")).status_code == 200
        valorisation = {"tauxTva": 0.20, "fournisseurId": ids["fournisseur"], "sousFamilleId": ids["acc"],
                        "prix": [{"ligneId": demande["lignes"][0]["id"], "prixUnitaireHt": prix_ht}]}

        # A : refus budgetaire attendu (enveloppe trop petite).
        r = await client.post(f"{base}/valorisation", json=valorisation)
        assert r.status_code == 422, r.text
        manque = r.json()["montantManquant"]

        # B : l'Acheteur demande un transfert INFO-MAT -> INFO-ACC du montant manquant.
        r = await client.post(f"{base}/ajustements", headers=entete("acheteur"), json={
            "type": "SOUS_FAMILLE", "montant": manque, "sourceSousFamilleId": ids["sous_famille"],
            "cibleSousFamilleId": ids["acc"], "justification": "Enveloppe accessoires insuffisante"})
        assert r.status_code == 201, r.text
        ajustement = r.json()
        assert ajustement["statut"] == "EN_ATTENTE_DAF"
        assert (await _statut(client, demande["id"]))["statut"] == "AJUSTEMENT_SOUS_FAMILLE_EN_COURS"
        base_aj = f"{base}/ajustements/{ajustement['id']}"

        # C : le DAF valide ; la demande revient a VALIDEE_TECHNIQUE.
        r = await client.post(f"{base_aj}/decision-daf", headers=entete("daf"), json={"valider": True})
        assert r.status_code == 200 and r.json()["statut"] == "EN_ATTENTE_ACHETEUR", r.text
        assert (await _statut(client, demande["id"]))["statut"] == "VALIDEE_TECHNIQUE"

        # D : defaut T1a conserve - la demande passe EN_VALORISATION sans aucune valorisation.
        r = await client.post(f"{base_aj}/confirmation-acheteur", headers=entete("acheteur"),
                              json={"justification": "Ajustement accorde"})
        assert r.status_code == 200 and r.json()["statut"] == "EN_ATTENTE_AMG", r.text
        etat = await _statut(client, demande["id"])
        assert etat["statut"] == "EN_VALORISATION"
        assert etat["montantTtc"] is None

        # E : la valorisation est desormais impossible (statut hors sequence).
        assert (await client.post(f"{base}/valorisation", json=valorisation)).status_code == 409

        # F : la chaine de validation continue malgre l'absence de montant.
        r = await client.post(f"{base_aj}/finalisation-amg", headers=entete("amg"))
        assert r.status_code == 200 and r.json()["statut"] == "VALIDE", r.text
        for etape, role in (("validation-amg", "amg"), ("validation-daf", "daf")):
            r = await client.post(f"{base}/{etape}", headers=entete(role), json={"valider": True})
            assert r.status_code == 200, f"{etape} : {r.text}"
        assert (await client.post(f"{base}/validation-dg")).status_code == 200
        etat = await _statut(client, demande["id"])
        assert etat["statut"] == "VALIDEE_DG"
        assert etat["montantTtc"] is None

        # G : symptome final - le bon de commande ne peut pas etre genere.
        r = await client.post(f"{base}/purchase-order")
        assert r.status_code == 500, r.text
        assert r.json()["code"] == "ERREUR_INTERNE"

        # Les traces de l'ajustement portent bien le businessCorrelationId de la demande.
        confirmation = await _trace(client, demande["id"], "DemandeAjustementService.confirmerAcheteur")
        assert confirmation["statut"] == "SUCCES"


async def test_ajustement_d_une_autre_demande_refuse_en_404(pool: asyncpg.Pool) -> None:  # noqa: F811
    ids = await _ids_p3(pool)
    ajustement_id = await pool.fetchval("SELECT max(id) FROM erp.demande_ajustement")
    autre_demande = await pool.fetchval(
        "SELECT id FROM erp.demande_achat_interne WHERE id NOT IN "
        "(SELECT demande_interne_id FROM erp.demande_ajustement WHERE id = $1) LIMIT 1", ajustement_id)
    assert ajustement_id is not None and autre_demande is not None
    async with httpx.AsyncClient(timeout=10) as client:
        r = await client.post(f"{ERP_URL}/api/demandes/{autre_demande}/ajustements/{ajustement_id}/finalisation-amg",
                              headers={"X-Utilisateur-Id": str(ids["demandeur"])})
    assert r.status_code == 404, r.text
    assert r.json()["code"] == "RESSOURCE_INTROUVABLE"
