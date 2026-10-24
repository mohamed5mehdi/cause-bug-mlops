"""Exporte un instantane de l'API d'aiops-engine pour la demo statique du tableau de bord.

Usage, plateforme lancee :
    python dashboard-aiops/scripts/exporter_demo.py [http://localhost:8000]

Ne garde que les dossiers de la grille d'evaluation (docs/evaluation/grille-mesure-2.csv),
dont la panne injectee est connue, et ecrit dans dashboard-aiops/public/demo/ :
dossiers.json, scenarios.json (id -> P1/P2/P3/T), traces/<id>.json, diagnostics/<id>.json.
Lecture seule : uniquement des GET, aucun diagnostic n'est lance.
"""

import csv
import json
import sys
import urllib.request
from pathlib import Path

API = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8000"
DASHBOARD = Path(__file__).resolve().parent.parent
DEST = DASHBOARD / "public" / "demo"
GRILLE = DASHBOARD.parent / "docs" / "evaluation" / "grille-mesure-2.csv"


def lire(chemin: str) -> bytes:
    with urllib.request.urlopen(f"{API}{chemin}", timeout=30) as reponse:
        return reponse.read()


def ecrire_json(chemin: Path, donnees: object) -> None:
    chemin.write_text(json.dumps(donnees, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    with GRILLE.open(encoding="utf-8-sig", newline="") as fichier:
        scenarios = {ligne["correlation_id"]: ligne["cas"] for ligne in csv.DictReader(fichier, delimiter=";")}

    for sous_dossier in ("traces", "diagnostics"):
        (DEST / sous_dossier).mkdir(parents=True, exist_ok=True)

    dossiers = [d for d in json.loads(lire("/dossiers?limite=50"))
                if d["business_correlation_id"] in scenarios]
    ecrire_json(DEST / "dossiers.json", dossiers)
    ecrire_json(DEST / "scenarios.json", {d["business_correlation_id"]: scenarios[d["business_correlation_id"]]
                                           for d in dossiers})

    for dossier in dossiers:
        ident = dossier["business_correlation_id"]
        (DEST / "traces" / f"{ident}.json").write_bytes(lire(f"/traces/{ident}"))
        (DEST / "diagnostics" / f"{ident}.json").write_bytes(lire(f"/diagnostics/{ident}"))

    print(f"{len(dossiers)} dossiers de la grille exportes dans {DEST}")


if __name__ == "__main__":
    main()
