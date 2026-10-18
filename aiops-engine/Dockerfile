# aiops-engine : image d'execution (cible par defaut) et image de test.
# La version de Python est figee ici, independamment du Python de la machine hote.

FROM python:3.12-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY app ./app
RUN useradd --create-home --uid 10001 aiops
USER aiops
EXPOSE 8000
# Sonde de sante sans curl (absent de l'image slim) : 503 ou erreur -> code 1.
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/health', timeout=2)"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

# Image de test : memes dependances + outils de test. Jamais deployee.
FROM runtime AS test
USER root
COPY requirements-dev.txt pytest.ini ./
RUN pip install -r requirements-dev.txt
COPY tests ./tests
USER aiops
CMD ["pytest", "-v"]
