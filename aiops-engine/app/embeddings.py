"""Embeddings : transformer un texte en vecteur de 768 nombres (ADR-012).

Modele : gemini-embedding-2 (SDK officiel google-genai). Points de vigilance,
verifies dans la documentation Gemini le 2026-10-19 :
- le type de tache ne passe pas par un parametre mais par un PREFIXE dans le texte
  ("title: ... | text: ..." pour un document, "task: search result | query: ..."
  pour une requete) ; documents et requetes doivent suivre ce format ;
- plusieurs textes envoyes dans un meme appel produisent UN SEUL vecteur fusionne :
  on envoie donc un texte par appel ;
- les vecteurs de 768 sont normalises par le modele ; on normalise quand meme
  (L2) pour ne pas dependre de ce comportement.
"""

import math
from typing import Protocol

import httpx
from google import genai
from google.genai import errors, types

DIMENSION = 768


class ErreurEmbedding(Exception):
    """Le vecteur n'a pas pu etre obtenu ou est inutilisable."""


def normaliser(vecteur: list[float]) -> list[float]:
    """Ramene le vecteur a une longueur (norme L2) de 1, sans changer sa direction.
    Un vecteur nul n'a pas de direction : il est refuse."""
    norme = math.sqrt(math.fsum(v * v for v in vecteur))
    if norme == 0.0:
        raise ErreurEmbedding("Vecteur nul : direction indefinie")
    return [v / norme for v in vecteur]


def texte_document(titre: str, contenu: str) -> str:
    """Format 'document' (ce que l'on range dans l'index)."""
    return f"title: {titre or 'none'} | text: {contenu}"


def texte_requete(question: str) -> str:
    """Format 'requete' (ce que l'on cherche dans l'index)."""
    return f"task: search result | query: {question}"


def en_litteral_pgvector(vecteur: list[float]) -> str:
    """Representation texte acceptee par PostgreSQL pour le type vector : '[x,y,...]'."""
    return "[" + ",".join(repr(float(v)) for v in vecteur) + "]"


class FournisseurEmbeddings(Protocol):
    """Contrat (equivalent d'une interface Java) : permet un faux en test."""

    nom_modele: str

    async def vectoriser(self, texte_prepare: str) -> list[float]: ...


class EmbeddingsGemini:
    """Implementation reelle via l'API Gemini (client asynchrone)."""

    def __init__(self, cle_api: str, nom_modele: str, delai_s: float = 30.0) -> None:
        self.nom_modele = nom_modele
        # Le SDK attend un delai en MILLISECONDES ; sans lui, httpx attend indefiniment.
        self._client = genai.Client(api_key=cle_api, http_options=types.HttpOptions(
            timeout=int(delai_s * 1000))).aio

    async def vectoriser(self, texte_prepare: str) -> list[float]:
        try:
            reponse = await self._client.models.embed_content(
                model=self.nom_modele,
                contents=texte_prepare,
                config=types.EmbedContentConfig(output_dimensionality=DIMENSION),
            )
        except errors.APIError as erreur:
            raise ErreurEmbedding(f"API Gemini : code {erreur.code}") from erreur
        except httpx.TimeoutException as erreur:
            # Un delai depasse leve une exception httpx, pas une APIError.
            raise ErreurEmbedding("API Gemini : delai depasse") from erreur
        if not reponse.embeddings or not reponse.embeddings[0].values:
            raise ErreurEmbedding("Reponse sans vecteur")
        valeurs = list(reponse.embeddings[0].values)
        if len(valeurs) != DIMENSION:
            raise ErreurEmbedding(f"Dimension inattendue : {len(valeurs)} au lieu de {DIMENSION}")
        return normaliser(valeurs)

    async def fermer(self) -> None:
        await self._client.aclose()
