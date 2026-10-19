"""Point d'entree FastAPI d'aiops-engine (equivalent de la classe @SpringBootApplication)."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

import asyncpg
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response, status

from app.config import Parametres
from app.connaissance import Vectoriseur, rechercher_similaires
from app.db import creer_pool, preparer_schema
from app.embeddings import EmbeddingsGemini, ErreurEmbedding
from app.ingestion import EcouteurTraces
from app.modeles import DocumentSimilaire, EtatSante, TraceEvenement
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
    if parametres.gemini_api_key is not None and parametres.gemini_api_key.get_secret_value():
        embeddings = EmbeddingsGemini(parametres.gemini_api_key.get_secret_value(),
                                      parametres.modele_embedding)
        vectoriseur = Vectoriseur(pool, embeddings)
        LOG.info("Vectorisation active (modele %s)", parametres.modele_embedding)
    else:
        LOG.warning("GEMINI_API_KEY absente : ingestion seule, vectorisation inactive")

    ecouteur = EcouteurTraces(parametres, pool, vectoriseur)
    await ecouteur.demarrer()
    app.state.pool = pool
    app.state.ecouteur = ecouteur
    app.state.embeddings = embeddings
    app.state.vectoriseur = vectoriseur
    LOG.info("aiops-engine demarre")
    try:
        yield
    finally:
        await ecouteur.arreter()
        if embeddings is not None:
            await embeddings.fermer()
        await pool.close()
        LOG.info("aiops-engine arrete")


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
