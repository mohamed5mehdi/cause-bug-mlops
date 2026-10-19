"""Ingestion des traces d'execution emises par le simulateur d'achats (ADR-011).

Principe : le simulateur ecrit chaque trace dans erp.trace_execution, puis un
trigger PostgreSQL envoie pg_notify('trace_execution', id). aiops-engine ecoute
ce canal et recopie la ligne dans aiops.transaction_trace_event.

- La notification ne porte que l'identifiant ; la donnee est relue en table.
- Au (re)demarrage de l'ecoute, un rattrapage recopie les traces emises pendant
  l'absence d'aiops-engine (une notification n'atteint que les sessions deja en
  ecoute).
- Chaque copie est idempotente (contrainte UNIQUE sur source_id) : une trace
  recue deux fois (notification + rattrapage) n'est stockee qu'une fois.
"""

import asyncio
import logging
from contextlib import suppress

import asyncpg

from app.config import Parametres
from app.connaissance import Vectoriseur
from app.db import ouvrir_connexion

LOG = logging.getLogger(__name__)

_COLONNES = (
    "debut, duree_ms, request_id, business_correlation_id, composant, operation, "
    "statut, exception_type, exception_message"
)

COPIE_UNE_TRACE = f"""
    INSERT INTO aiops.transaction_trace_event (source_id, {_COLONNES})
    SELECT id, {_COLONNES} FROM erp.trace_execution WHERE id = $1
    ON CONFLICT (source_id) DO NOTHING
"""

RATTRAPAGE = f"""
    INSERT INTO aiops.transaction_trace_event (source_id, {_COLONNES})
    SELECT id, {_COLONNES} FROM erp.trace_execution
    ON CONFLICT (source_id) DO NOTHING
"""


def lire_identifiant(charge: str) -> int | None:
    """Convertit la charge d'une notification en identifiant de trace.
    Retourne None si la charge n'est pas un entier strictement positif."""
    try:
        valeur = int(charge)
    except ValueError:
        return None
    return valeur if valeur > 0 else None


def _lignes_inserees(statut: str) -> int:
    """Statut renvoye par PostgreSQL pour un INSERT : 'INSERT 0 <nombre>'."""
    return int(statut.split()[-1])


async def copier_trace(pool: asyncpg.Pool, source_id: int) -> bool:
    """Copie une trace ; True si elle etait nouvelle, False si deja copiee."""
    return _lignes_inserees(await pool.execute(COPIE_UNE_TRACE, source_id)) == 1


async def rattraper(pool: asyncpg.Pool) -> int:
    """Copie toutes les traces absentes ; retourne le nombre de traces copiees."""
    return _lignes_inserees(await pool.execute(RATTRAPAGE))


class EcouteurTraces:
    """Tache de fond : ecoute le canal, copie les traces, se reconnecte seule."""

    def __init__(self, parametres: Parametres, pool: asyncpg.Pool,
                 vectoriseur: Vectoriseur | None = None) -> None:
        self._parametres = parametres
        self._pool = pool
        self._vectoriseur = vectoriseur
        self._arret = asyncio.Event()
        self._tache: asyncio.Task[None] | None = None
        self._taches_vectorisation: set[asyncio.Task[None]] = set()
        self.connecte = False
        self.traces_ingerees = 0

    def _declencher_vectorisation(self) -> None:
        """Lance la vectorisation des nouveaux echecs en tache de fond (sans attendre l'API)."""
        if self._vectoriseur is None:
            return
        tache = asyncio.create_task(self._vectoriseur.rattraper())
        # Garder une reference : asyncio ne conserve qu'une reference faible aux taches.
        self._taches_vectorisation.add(tache)
        tache.add_done_callback(self._taches_vectorisation.discard)

    async def demarrer(self) -> None:
        self._tache = asyncio.create_task(self._boucle(), name="ecouteur-traces")

    async def arreter(self) -> None:
        self._arret.set()
        if self._tache is not None:
            self._tache.cancel()
            with suppress(asyncio.CancelledError):
                await self._tache

    async def _boucle(self) -> None:
        delai_s = 1.0
        while not self._arret.is_set():
            connexion: asyncpg.Connection | None = None
            perdue = asyncio.Event()
            try:
                connexion = await ouvrir_connexion(self._parametres)
                connexion.add_termination_listener(lambda _c: perdue.set())
                await connexion.add_listener(self._parametres.canal_traces, self._sur_notification)
                self.connecte = True
                # Ecouter AVANT de rattraper : aucune trace ne tombe entre les deux.
                rattrapees = await rattraper(self._pool)
                self.traces_ingerees += rattrapees
                LOG.info("Ecoute du canal '%s' active ; rattrapage : %d trace(s)",
                         self._parametres.canal_traces, rattrapees)
                self._declencher_vectorisation()
                delai_s = 1.0
                await perdue.wait()
                LOG.warning("Connexion d'ecoute perdue")
            except asyncio.CancelledError:
                raise
            except Exception as erreur:  # la boucle de fond ne doit jamais s'arreter seule
                LOG.warning("Ecoute indisponible (%s) ; nouvel essai dans %.0f s", erreur, delai_s)
            finally:
                self.connecte = False
                if connexion is not None and not connexion.is_closed():
                    await connexion.close()
            if not self._arret.is_set():
                await asyncio.sleep(delai_s)
                delai_s = min(delai_s * 2, self._parametres.delai_reconnexion_max_s)

    async def _sur_notification(self, _connexion: asyncpg.Connection, _pid: int,
                                _canal: str, charge: str) -> None:
        source_id = lire_identifiant(charge)
        if source_id is None:
            LOG.warning("Notification ignoree : charge invalide %r", charge[:50])
            return
        try:
            if await copier_trace(self._pool, source_id):
                self.traces_ingerees += 1
                self._declencher_vectorisation()
        except Exception:
            LOG.exception("Copie de la trace %d impossible", source_id)
