"""Parametres lus dans l'environnement (equivalent de @ConfigurationProperties)."""

from pathlib import Path

from pydantic import AliasChoices, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Parametres(BaseSettings):
    """Configuration d'aiops-engine. Chaque champ correspond a une variable
    d'environnement de meme nom en majuscules (ex. db_host <- DB_HOST)."""

    model_config = SettingsConfigDict(extra="ignore")

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "causebugmlops"
    db_user: str = "aiops_reader"
    # Accepte DB_PASSWORD (Compose) ou AIOPS_READER_PASSWORD (fichier .env).
    db_password: SecretStr = Field(validation_alias=AliasChoices("DB_PASSWORD", "AIOPS_READER_PASSWORD"))

    # Facultative : sans cle, l'ingestion fonctionne mais la vectorisation est inactive.
    gemini_api_key: SecretStr | None = None
    modele_embedding: str = "gemini-embedding-2"
    modele_diagnostic: str = "gemini-3.8-flash"
    # Secours en cas d'indisponibilite passagere du modele principal (503 observe le 2026-10-19).
    modele_diagnostic_secours: str = "gemini-3.5-flash-lite"
    # Delais (ADR-015) : sans eux, un appel Gemini lent bloque indefiniment (179 s observees
    # le 2026-10-20). Pire cas de generation : 2 modeles x 2 tentatives x 30 s + 2 x 1 s = 122 s.
    delai_appel_gemini_s: float = 30.0
    tentatives_gemini: int = 2
    budget_diagnostic_s: float = 130.0
    fichier_regles: str = str(Path(__file__).resolve().parent.parent / "connaissance" / "regles-metier.md")

    canal_traces: str = "trace_execution"
    delai_reconnexion_max_s: float = 30.0
