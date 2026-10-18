"""Parametres lus dans l'environnement (equivalent de @ConfigurationProperties)."""

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

    canal_traces: str = "trace_execution"
    delai_reconnexion_max_s: float = 30.0
