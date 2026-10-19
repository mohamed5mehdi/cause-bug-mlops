"""Base de connaissance vectorielle (pgvector) : ranger et retrouver par le sens (ADR-012).

- Chaque document (trace d'echec maintenant, regle metier a J6) est stocke avec son
  vecteur dans aiops.document_vectorise.
- La recherche classe les documents par distance cosinus (operateur <=>), le meme
  operateur que celui de l'index HNSW (vector_cosine_ops) : sinon l'index est ignore.
- similarite = 1 - distance cosinus (1 = meme sens, 0 = sans rapport).
"""

import asyncio
import logging
from dataclasses import dataclass

import asyncpg

from app.embeddings import (
    ErreurEmbedding,
    FournisseurEmbeddings,
    en_litteral_pgvector,
    texte_document,
    texte_requete,
)

LOG = logging.getLogger(__name__)

TYPE_TRACE_ECHEC = "TRACE_ECHEC"

ECHECS_NON_VECTORISES = """
    SELECT t.source_id, t.composant, t.operation, t.exception_type, t.exception_message
    FROM aiops.transaction_trace_event t
    WHERE t.statut = 'ECHEC'
      AND NOT EXISTS (SELECT 1 FROM aiops.document_vectorise d
                      WHERE d.type_document = $1 AND d.reference = t.source_id::text
                        AND d.modele = $3)
    ORDER BY t.source_id
    LIMIT $2
"""

INSERTION = """
    INSERT INTO aiops.document_vectorise (type_document, reference, texte, modele, embedding)
    VALUES ($1, $2, $3, $4, $5::vector)
    ON CONFLICT (type_document, reference, modele) DO NOTHING
"""

RECHERCHE = """
    SELECT type_document, reference, texte, 1 - (embedding <=> $1::vector) AS similarite
    FROM aiops.document_vectorise
    WHERE modele = $2
    ORDER BY embedding <=> $1::vector
    LIMIT $3
"""


@dataclass(frozen=True)
class DocumentTrouve:
    type_document: str
    reference: str
    texte: str
    similarite: float


def decrire_echec(composant: str, operation: str, exception_type: str | None,
                  message: str | None) -> tuple[str, str]:
    """Titre et contenu textuels d'une trace d'echec, tels qu'ils seront vectorises."""
    exception = (exception_type or "exception inconnue").rsplit(".", 1)[-1]
    return f"{composant}.{operation}", f"Echec {exception} : {message or 'sans message'}"


async def vectoriser_echecs_manquants(pool: asyncpg.Pool, fournisseur: FournisseurEmbeddings,
                                      limite: int = 50) -> int:
    """Vectorise les traces d'echec qui n'ont pas encore de vecteur.
    Idempotent ; s'arrete a la premiere erreur d'API (reprise au prochain appel)."""
    lignes = await pool.fetch(ECHECS_NON_VECTORISES, TYPE_TRACE_ECHEC, limite, fournisseur.nom_modele)
    traitees = 0
    for ligne in lignes:
        titre, contenu = decrire_echec(ligne["composant"], ligne["operation"],
                                       ligne["exception_type"], ligne["exception_message"])
        try:
            vecteur = await fournisseur.vectoriser(texte_document(titre, contenu))
        except ErreurEmbedding as erreur:
            LOG.warning("Vectorisation interrompue (trace %d) : %s", ligne["source_id"], erreur)
            break
        await pool.execute(INSERTION, TYPE_TRACE_ECHEC, str(ligne["source_id"]),
                           f"{titre} | {contenu}", fournisseur.nom_modele,
                           en_litteral_pgvector(vecteur))
        traitees += 1
    return traitees


async def rechercher_similaires(pool: asyncpg.Pool, fournisseur: FournisseurEmbeddings,
                                question: str, k: int = 5) -> list[DocumentTrouve]:
    """Les k documents les plus proches du sens de la question."""
    vecteur = await fournisseur.vectoriser(texte_requete(question))
    lignes = await pool.fetch(RECHERCHE, en_litteral_pgvector(vecteur), fournisseur.nom_modele, k)
    return [DocumentTrouve(l["type_document"], l["reference"], l["texte"], float(l["similarite"]))
            for l in lignes]


class Vectoriseur:
    """Declenche la vectorisation sans jamais en lancer deux en parallele."""

    def __init__(self, pool: asyncpg.Pool, fournisseur: FournisseurEmbeddings) -> None:
        self._pool = pool
        self._fournisseur = fournisseur
        self._verrou = asyncio.Lock()
        self.documents_vectorises = 0

    async def rattraper(self) -> None:
        async with self._verrou:
            try:
                n = await vectoriser_echecs_manquants(self._pool, self._fournisseur)
            except Exception:
                LOG.exception("Vectorisation impossible")
                return
            self.documents_vectorises += n
            if n:
                LOG.info("%d trace(s) d'echec vectorisee(s)", n)
