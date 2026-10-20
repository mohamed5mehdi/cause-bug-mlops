"""Lectures pour le tableau de bord : liste des dossiers et diagnostics passes.
Lecture seule, agregation faite par PostgreSQL (equivalent de @Query avec projection DTO)."""

from uuid import UUID

import asyncpg

from app.modeles import DiagnosticHistorique, DossierResume

DOSSIERS = """
    SELECT business_correlation_id,
           count(*)                                AS nb_traces,
           count(*) FILTER (WHERE statut = 'ECHEC') AS nb_echecs,
           min(debut)                              AS premiere_trace,
           max(debut)                              AS derniere_trace
    FROM aiops.transaction_trace_event
    WHERE business_correlation_id IS NOT NULL
    GROUP BY business_correlation_id
    ORDER BY derniere_trace DESC, business_correlation_id
    LIMIT $1
"""

DIAGNOSTICS = """
    SELECT id, business_correlation_id, cause_racine, confiance, composant, preuves,
           preuves_rejetees, modele, latence_ms, jetons_entree, jetons_sortie, cree_le
    FROM aiops.diagnostic
    WHERE business_correlation_id = $1
    ORDER BY cree_le DESC, id DESC
"""


def _uuid(valeur: object) -> UUID:
    # asyncpg renvoie son propre type UUID : conversion explicite.
    return UUID(str(valeur))


async def lister_dossiers(pool: asyncpg.Pool, limite: int) -> list[DossierResume]:
    """Les dossiers les plus recemment actifs, un par businessCorrelationId."""
    lignes = await pool.fetch(DOSSIERS, limite)
    return [
        DossierResume(
            business_correlation_id=_uuid(ligne["business_correlation_id"]),
            nb_traces=ligne["nb_traces"],
            nb_echecs=ligne["nb_echecs"],
            premiere_trace=ligne["premiere_trace"],
            derniere_trace=ligne["derniere_trace"],
        )
        for ligne in lignes
    ]


async def lire_diagnostics(pool: asyncpg.Pool, correlation_id: UUID) -> list[DiagnosticHistorique]:
    """Tous les diagnostics d'un dossier, du plus recent au plus ancien."""
    lignes = await pool.fetch(DIAGNOSTICS, correlation_id)
    return [
        DiagnosticHistorique(
            id=ligne["id"],
            business_correlation_id=_uuid(ligne["business_correlation_id"]),
            cause_racine=ligne["cause_racine"],
            # NUMERIC(4,3) arrive en Decimal : conversion en float pour le JSON.
            confiance=float(ligne["confiance"]),
            composant=ligne["composant"],
            preuves=list(ligne["preuves"]),
            nb_preuves_rejetees=ligne["preuves_rejetees"],
            modele=ligne["modele"],
            latence_ms=ligne["latence_ms"],
            jetons_entree=ligne["jetons_entree"],
            jetons_sortie=ligne["jetons_sortie"],
            cree_le=ligne["cree_le"],
        )
        for ligne in lignes
    ]
