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
      AND ($4::text[] IS NULL OR type_document = ANY($4::text[]))
      AND NOT (reference = ANY($5::text[]))
    ORDER BY embedding <=> $1::vector
    LIMIT $3
"""

TYPE_REGLE_METIER = "REGLE_METIER"

REGLES_EXISTANTES = """
    SELECT reference, texte FROM aiops.document_vectorise
    WHERE type_document = $1 AND modele = $2
"""

UPSERT_REGLE = """
    INSERT INTO aiops.document_vectorise (type_document, reference, texte, modele, embedding)
    VALUES ($1, $2, $3, $4, $5::vector)
    ON CONFLICT (type_document, reference, modele)
    DO UPDATE SET texte = EXCLUDED.texte, embedding = EXCLUDED.embedding, cree_le = now()
"""


@dataclass(frozen=True)
class RegleMetier:
    reference: str
    titre: str
    contenu: str

    @property
    def texte(self) -> str:
        return f"{self.reference} {self.titre} | {self.contenu}"


def lire_regles(markdown: str) -> list[RegleMetier]:
    """Decoupe le corpus : une regle par section '## R-xx Titre'."""
    regles: list[RegleMetier] = []
    entete: tuple[str, str] | None = None
    lignes: list[str] = []
    for ligne in markdown.splitlines() + ["## FIN"]:
        if ligne.startswith("## "):
            if entete is not None:
                contenu = " ".join(l.strip() for l in lignes if l.strip())
                regles.append(RegleMetier(entete[0], entete[1], contenu))
            titre = ligne[3:].strip()
            reference, _, reste = titre.partition(" ")
            entete = (reference, reste.strip()) if reference.startswith("R-") else None
            lignes = []
        elif entete is not None:
            lignes.append(ligne)
    return regles


async def synchroniser_regles(pool: asyncpg.Pool, fournisseur: FournisseurEmbeddings,
                              regles: list[RegleMetier]) -> int:
    """Aligne la base vectorielle sur le corpus : vectorise les regles nouvelles ou
    modifiees, retire celles qui ont disparu. Retourne le nombre de regles vectorisees."""
    existantes = {r["reference"]: r["texte"] for r in
                  await pool.fetch(REGLES_EXISTANTES, TYPE_REGLE_METIER, fournisseur.nom_modele)}
    vectorisees = 0
    for regle in regles:
        if existantes.get(regle.reference) == regle.texte:
            continue
        vecteur = await fournisseur.vectoriser(texte_document(f"{regle.reference} {regle.titre}", regle.contenu))
        await pool.execute(UPSERT_REGLE, TYPE_REGLE_METIER, regle.reference, regle.texte,
                           fournisseur.nom_modele, en_litteral_pgvector(vecteur))
        vectorisees += 1
    retirees = set(existantes) - {r.reference for r in regles}
    if retirees:
        await pool.execute(
            "DELETE FROM aiops.document_vectorise WHERE type_document = $1 AND modele = $2 "
            "AND reference = ANY($3::text[])", TYPE_REGLE_METIER, fournisseur.nom_modele, list(retirees))
    return vectorisees


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
                                question: str, k: int = 5, types: list[str] | None = None,
                                exclure: list[str] | None = None) -> list[DocumentTrouve]:
    """Les k documents les plus proches du sens de la question.
    types : restreint aux types de documents donnes ; exclure : references a ignorer."""
    vecteur = await fournisseur.vectoriser(texte_requete(question))
    lignes = await pool.fetch(RECHERCHE, en_litteral_pgvector(vecteur), fournisseur.nom_modele, k,
                              types, exclure or [])
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
