"""Lecture des traces copiees (equivalent d'un repository Spring Data)."""

from uuid import UUID

import asyncpg

from app.modeles import StatutTrace, TraceEvenement

CHRONOLOGIE = """
    SELECT source_id, debut, duree_ms, request_id, business_correlation_id,
           composant, operation, statut, exception_type, exception_message
    FROM aiops.transaction_trace_event
    WHERE business_correlation_id = $1
    ORDER BY debut, source_id
"""


async def lire_chronologie(pool: asyncpg.Pool, correlation_id: UUID) -> list[TraceEvenement]:
    """Toutes les traces d'un dossier, dans l'ordre chronologique."""
    lignes = await pool.fetch(CHRONOLOGIE, correlation_id)
    return [
        TraceEvenement(
            source_id=ligne["source_id"],
            debut=ligne["debut"],
            duree_ms=ligne["duree_ms"],
            request_id=ligne["request_id"],
            # asyncpg renvoie son propre type UUID : conversion explicite.
            business_correlation_id=UUID(str(ligne["business_correlation_id"]))
            if ligne["business_correlation_id"] is not None else None,
            composant=ligne["composant"],
            operation=ligne["operation"],
            statut=StatutTrace(ligne["statut"]),
            exception_type=ligne["exception_type"],
            exception_message=ligne["exception_message"],
        )
        for ligne in lignes
    ]
