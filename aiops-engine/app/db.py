"""Acces a PostgreSQL : pool de connexions et preparation du schema aiops."""

from pathlib import Path

import asyncpg

from app.config import Parametres

FICHIER_SCHEMA = Path(__file__).parent / "schema.sql"


async def creer_pool(parametres: Parametres) -> asyncpg.Pool:
    """Pool de connexions (equivalent du pool HikariCP cote Spring)."""
    return await asyncpg.create_pool(
        host=parametres.db_host,
        port=parametres.db_port,
        database=parametres.db_name,
        user=parametres.db_user,
        password=parametres.db_password.get_secret_value(),
        min_size=1,
        max_size=5,
        command_timeout=10,
    )


async def ouvrir_connexion(parametres: Parametres) -> asyncpg.Connection:
    """Connexion isolee, hors pool : necessaire pour LISTEN, car une connexion
    rendue au pool perd ses ecouteurs (UNLISTEN *)."""
    return await asyncpg.connect(
        host=parametres.db_host,
        port=parametres.db_port,
        database=parametres.db_name,
        user=parametres.db_user,
        password=parametres.db_password.get_secret_value(),
    )


async def preparer_schema(pool: asyncpg.Pool) -> None:
    """Cree les tables d'aiops-engine si elles n'existent pas."""
    await pool.execute(FICHIER_SCHEMA.read_text(encoding="utf-8"))
