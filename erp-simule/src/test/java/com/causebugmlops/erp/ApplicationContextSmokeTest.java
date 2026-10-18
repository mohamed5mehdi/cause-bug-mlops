package com.causebugmlops.erp;

import com.causebugmlops.erp.repository.PoNumeroCompteurRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou de demarrage (ADR-003) : demarre le contexte Spring complet contre un
 * PostgreSQL 16 + pgvector ephemere, avec la configuration reelle
 * {@code ddl-auto=validate}.
 * <p>
 * Les tests de constat existants forcent {@code ddl-auto=none} et remplacent
 * {@link PoNumeroCompteurRepository} par un mock : ils ne pouvaient pas detecter
 * les defauts E1 (schema) et E2 (repository) qui empechaient le demarrage.
 */
@Testcontainers
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class ApplicationContextSmokeTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("causebugmlops");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PoNumeroCompteurRepository poNumeroCompteurRepository;

    @Test
    void leContexteDemarreEtToutesLesMigrationsSontAppliquees() {
        Integer migrationsReussies = jdbc.queryForObject(
                "SELECT count(*) FROM erp.flyway_schema_history WHERE success AND version IS NOT NULL",
                Integer.class);
        String derniereVersion = jdbc.queryForObject(
                "SELECT max(version::int)::text FROM erp.flyway_schema_history WHERE success AND version IS NOT NULL",
                String.class);

        assertThat(migrationsReussies).isEqualTo(5);
        assertThat(derniereVersion).isEqualTo("5");
    }

    @Test
    void ligneDeCommandeReferenceLaLigneDeDemandeEtPlusLArticle() {
        List<String> colonnes = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'erp' AND table_name = 'ligne_purchase_order'",
                String.class);

        assertThat(colonnes).contains("ligne_demande_id").doesNotContain("article_id");
    }

    @Test
    void leCompteurDeNumeroDePoFonctionneHorsSpringData() {
        String premier = poNumeroCompteurRepository.getNextPoNumero();
        String second = poNumeroCompteurRepository.getNextPoNumero();

        assertThat(premier).matches("PO-\\d{8}-\\d{4}");
        assertThat(second).matches("PO-\\d{8}-\\d{4}").isNotEqualTo(premier);
    }
}
