"""Point d'entree FastAPI d'aiops-engine (equivalent de la classe @SpringBootApplication)."""

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated
from uuid import UUID

import asyncpg
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response, status

from app.config import Parametres
from app.connaissance import Vectoriseur, lire_regles, rechercher_similaires, synchroniser_regles
from app.db import creer_pool, preparer_schema
from app.diagnostic import DossierSansTrace, ErreurDiagnostic, GenerateurGemini, diagnostiquer
from app.embeddings import EmbeddingsGemini, ErreurEmbedding
from app.historique import lire_diagnostics, lister_dossiers
from app.ingestion import EcouteurTraces
from app.modeles import (ContexteUtilise, DiagnosticHistorique, DiagnosticReponse, DocumentSimilaire,
                         DossierResume, EtatSante, TraceEvenement)
from app.traces import lire_chronologie

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")
LOG = logging.getLogger("aiops-engine")


@asynccontextmanager
async def cycle_de_vie(app: FastAPI) -> AsyncIterator[None]:
    """Demarrage puis arret propre (equivalent de @PostConstruct / @PreDestroy)."""
    parametres = Parametres()
    pool = await creer_pool(parametres)
    await preparer_schema(pool)

    embeddings: EmbeddingsGemini | None = None
    vectoriseur: Vectoriseur | None = None
    generateur: GenerateurGemini | None = None
    tache_regles: asyncio.Task[None] | None = None
    if parametres.gemini_api_key is not None and parametres.gemini_api_key.get_secret_value():
        cle = parametres.gemini_api_key.get_secret_value()
        embeddings = EmbeddingsGemini(cle, parametres.modele_embedding, delai_s=parametres.delai_appel_gemini_s)
        vectoriseur = Vectoriseur(pool, embeddings)
        generateur = GenerateurGemini(cle, parametres.modele_diagnostic, parametres.modele_diagnostic_secours,
                                      tentatives=parametres.tentatives_gemini,
                                      delai_s=parametres.delai_appel_gemini_s)
        tache_regles = asyncio.create_task(_charger_regles(pool, embeddings, parametres.fichier_regles))
        LOG.info("IA active (embeddings %s, diagnostic %s, secours %s)", parametres.modele_embedding,
                 parametres.modele_diagnostic, parametres.modele_diagnostic_secours)
    else:
        LOG.warning("GEMINI_API_KEY absente : ingestion seule, vectorisation et diagnostic inactifs")

    ecouteur = EcouteurTraces(parametres, pool, vectoriseur)
    await ecouteur.demarrer()
    app.state.pool = pool
    app.state.ecouteur = ecouteur
    app.state.budget_diagnostic_s = parametres.budget_diagnostic_s
    app.state.embeddings = embeddings
    app.state.vectoriseur = vectoriseur
    app.state.generateur = generateur
    LOG.info("aiops-engine demarre")
    try:
        yield
    finally:
        await ecouteur.arreter()
        if tache_regles is not None and not tache_regles.done():
            tache_regles.cancel()
        if embeddings is not None:
            await embeddings.fermer()
        if generateur is not None:
            await generateur.fermer()
        await pool.close()
        LOG.info("aiops-engine arrete")


async def _charger_regles(pool: asyncpg.Pool, embeddings: EmbeddingsGemini, fichier: str) -> None:
    """Vectorise le corpus de regles metier en tache de fond (sans bloquer le demarrage)."""
    try:
        regles = lire_regles(Path(fichier).read_text(encoding="utf-8"))
        n = await synchroniser_regles(pool, embeddings, regles)
        LOG.info("Regles metier : %d au corpus, %d (re)vectorisee(s)", len(regles), n)
    except Exception:
        LOG.exception("Chargement des regles metier impossible")


app = FastAPI(title="aiops-engine", version="0.2.0", lifespan=cycle_de_vie)


def fournir_pool(request: Request) -> asyncpg.Pool:
    return request.app.state.pool


def fournir_ecouteur(request: Request) -> EcouteurTraces:
    return request.app.state.ecouteur


Pool = Annotated[asyncpg.Pool, Depends(fournir_pool)]
Ecouteur = Annotated[EcouteurTraces, Depends(fournir_ecouteur)]


@app.get("/health", response_model=EtatSante)
async def sante(request: Request, response: Response, pool: Pool, ecouteur: Ecouteur) -> EtatSante:
    """UP si la base repond ET si l'ecoute des traces est active ; sinon 503.
    L'absence de cle Gemini n'est pas une panne : la vectorisation est signalee INACTIVE."""
    try:
        base = await pool.fetchval("SELECT 1") == 1
    except Exception:
        base = False
    disponible = base and ecouteur.connecte
    if not disponible:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    vectoriseur: Vectoriseur | None = request.app.state.vectoriseur
    return EtatSante(statut="UP" if disponible else "DOWN", base=base,
                     ecoute=ecouteur.connecte, traces_ingerees=ecouteur.traces_ingerees,
                     vectorisation="ACTIVE" if vectoriseur is not None else "INACTIVE",
                     documents_vectorises=vectoriseur.documents_vectorises if vectoriseur else 0)


@app.get("/traces/{correlation_id}", response_model=list[TraceEvenement])
async def chronologie(correlation_id: UUID, pool: Pool) -> list[TraceEvenement]:
    """Chronologie technique d'un dossier (businessCorrelationId = id de la demande)."""
    return await lire_chronologie(pool, correlation_id)


@app.get("/dossiers", response_model=list[DossierResume])
async def dossiers(pool: Pool, limite: Annotated[int, Query(ge=1, le=200)] = 50) -> list[DossierResume]:
    """Les dossiers les plus recemment actifs, avec leur nombre de traces et d'echecs."""
    return await lister_dossiers(pool, limite)


@app.get("/diagnostics/{correlation_id}", response_model=list[DiagnosticHistorique])
async def diagnostics(correlation_id: UUID, pool: Pool) -> list[DiagnosticHistorique]:
    """Historique des diagnostics d'un dossier, du plus recent au plus ancien (liste vide si aucun)."""
    return await lire_diagnostics(pool, correlation_id)


@app.post("/diagnose/{correlation_id}", response_model=DiagnosticReponse)
async def diagnose(request: Request, correlation_id: UUID, pool: Pool) -> DiagnosticReponse:
    """Diagnostic de cause racine d'un dossier par RAG.
    404 : aucune trace pour ce dossier ; 502 : le modele n'a pas fourni de reponse
    exploitable ; 503 : IA inactive (cle Gemini absente) ; 504 : budget de temps depasse."""
    embeddings: EmbeddingsGemini | None = request.app.state.embeddings
    generateur: GenerateurGemini | None = request.app.state.generateur
    if embeddings is None or generateur is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Diagnostic inactif (cle Gemini absente)")
    budget_s: float = request.app.state.budget_diagnostic_s
    try:
        # Budget global (ADR-015) : un echec franc vaut mieux qu'un ecran bloque.
        async with asyncio.timeout(budget_s):
            d = await diagnostiquer(pool, embeddings, generateur, correlation_id)
    except TimeoutError as erreur:
        LOG.warning("Diagnostic %s : budget de %.0f s depasse", correlation_id, budget_s)
        raise HTTPException(status.HTTP_504_GATEWAY_TIMEOUT, "Diagnostic trop long, reessayer") from erreur
    except DossierSansTrace as erreur:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Aucune trace pour ce dossier") from erreur
    except ErreurDiagnostic as erreur:
        LOG.warning("Diagnostic %s impossible : %s", correlation_id, erreur)
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, f"Diagnostic impossible : {erreur}") from erreur
    return DiagnosticReponse(
        business_correlation_id=d.business_correlation_id, cause_racine=d.cause_racine,
        confiance=round(d.confiance, 3), composant=d.composant.value, preuves=d.preuves,
        preuves_rejetees=d.preuves_rejetees, modele=d.modele, latence_ms=d.latence_ms,
        contexte=ContexteUtilise(traces=d.nb_traces, incidents_similaires=d.nb_incidents,
                                 regles_metier=d.nb_regles))


@app.get("/recherche", response_model=list[DocumentSimilaire])
async def recherche(request: Request, pool: Pool,
                    q: Annotated[str, Query(min_length=3, max_length=500)],
                    k: Annotated[int, Query(ge=1, le=20)] = 5) -> list[DocumentSimilaire]:
    """Les k documents de la base de connaissance les plus proches du sens de q."""
    embeddings: EmbeddingsGemini | None = request.app.state.embeddings
    if embeddings is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Vectorisation inactive (cle Gemini absente)")
    try:
        trouves = await rechercher_similaires(pool, embeddings, q, k)
    except ErreurEmbedding as erreur:
        LOG.warning("Recherche impossible : %s", erreur)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Service d'embedding indisponible") from erreur
    return [DocumentSimilaire(type_document=d.type_document, reference=d.reference,
                              texte=d.texte, similarite=round(d.similarite, 4)) for d in trouves]
