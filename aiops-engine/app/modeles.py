"""Contrats de donnees exposes par l'API (equivalent des records DTO Java)."""

from datetime import datetime
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict


class StatutTrace(StrEnum):
    SUCCES = "SUCCES"
    ECHEC = "ECHEC"


class TraceEvenement(BaseModel):
    """Un appel de service du simulateur d'achats, tel que copie par aiops-engine."""

    model_config = ConfigDict(frozen=True)

    source_id: int
    debut: datetime
    duree_ms: int
    request_id: str | None
    business_correlation_id: UUID | None
    composant: str
    operation: str
    statut: StatutTrace
    exception_type: str | None
    exception_message: str | None


class EtatSante(BaseModel):
    statut: Literal["UP", "DOWN"]
    base: bool
    ecoute: bool
    traces_ingerees: int
    vectorisation: Literal["ACTIVE", "INACTIVE"]
    documents_vectorises: int


class ContexteUtilise(BaseModel):
    traces: int
    incidents_similaires: int
    regles_metier: int


class DiagnosticReponse(BaseModel):
    """Resultat de POST /diagnose/{correlationId}."""

    business_correlation_id: UUID
    cause_racine: str
    confiance: float
    composant: Literal["CODE_JAVA", "BASE_DE_DONNEES", "FLUX_METIER"]
    preuves: list[str]
    preuves_rejetees: list[str]
    modele: str
    latence_ms: int
    contexte: ContexteUtilise


class DocumentSimilaire(BaseModel):
    """Un document de la base de connaissance et sa proximite de sens avec la requete."""

    type_document: str
    reference: str
    texte: str
    similarite: float
