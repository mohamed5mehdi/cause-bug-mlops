# syntax=docker/dockerfile:1
# ----------------------------------------------------------------------------
# Etape 1 : build du jar executable (JDK + Maven)
# Les tests ne tournent pas ici : ils demarrent leurs propres conteneurs
# (Testcontainers) et s'executent via ./mvnw test sur la machine de dev ou en CI.
# ----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Le cache .m2 survit entre les builds (BuildKit) : seules les dependances
# modifiees sont retelechargees.
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
 && cp target/erp-simule-*.jar /build/app.jar

# ----------------------------------------------------------------------------
# Etape 2 : image d'execution minimale (JRE seul, utilisateur non-root)
# ----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system erp && useradd --system --gid erp --no-create-home erp
COPY --from=build --chown=erp:erp /build/app.jar /app/app.jar
USER erp

EXPOSE 8080
# Sonde de sante : /actuator/health renvoie {"status":"UP"} quand la base est joignable.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
