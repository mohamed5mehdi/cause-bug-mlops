"""Point d'entree FastAPI d'aiops-engine (equivalent de la classe @SpringBootApplication)."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

import asyncpg
from fastapi import Depends, FastAPI, Request, Response, status

from app.config import Parametres
from app.db import creer_pool, preparer_schema
from app.ingestion import EcouteurTraces
from app.modeles import EtatSante, TraceEvenement
from app.traces import lire_chronologie

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")
LOG = logging.getLogger("aiops-engine")


@asynccontextmanager
async def cycle_de_vie(app: FastAPI) -> AsyncIterator[None]:
    """Demarrage puis arret propre (equivalent de @PostConstruct / @PreDestroy)."""
    parametres = Parametres()
    pool = await creer_pool(parametres)
    await preparer_schema(pool)
    ecouteur = EcouteurTraces(parametres, pool)
    await ecouteur.demarrer()
    app.state.pool = pool
    app.state.ecouteur = ecouteur
    LOG.info("aiops-engine demarre")
    try:
        yield
    finally:
        await ecouteur.arreter()
        await pool.close()
        LOG.info("aiops-engine arrete")


app = FastAPI(title="aiops-engine", version="0.1.0", lifespan=cycle_de_vie)


def fournir_pool(request: Request) -> asyncpg.Pool:
    return request.app.state.pool


def fournir_ecouteur(request: Request) -> EcouteurTraces:
    return request.app.state.ecouteur


Pool = Annotated[asyncpg.Pool, Depends(fournir_pool)]
Ecouteur = Annotated[EcouteurTraces, Depends(fournir_ecouteur)]


@app.get("/health", response_model=EtatSante)
async def sante(response: Response, pool: Pool, ecouteur: Ecouteur) -> EtatSante:
    """UP si la base repond ET si l'ecoute des traces est active ; sinon 503."""
    try:
        base = await pool.fetchval("SELECT 1") == 1
    except Exception:
        base = False
    disponible = base and ecouteur.connecte
    if not disponible:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return EtatSante(statut="UP" if disponible else "DOWN", base=base,
                     ecoute=ecouteur.connecte, traces_ingerees=ecouteur.traces_ingerees)


@app.get("/traces/{correlation_id}", response_model=list[TraceEvenement])
async def chronologie(correlation_id: UUID, pool: Pool) -> list[TraceEvenement]:
    """Chronologie technique d'un dossier (businessCorrelationId = id de la demande)."""
    return await lire_chronologie(pool, correlation_id)
