package com.causebugmlops.erp.api.tracabilite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests unitaires du filtre requestId (sans Spring ni base de donnees).
 */
class FiltreRequestIdTest {

    private static final String FORMAT_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final FiltreRequestId filtre = new FiltreRequestId();

    @AfterEach
    void viderMdc() {
        MDC.clear();
    }

    private MockHttpServletResponse executer(String enTeteRecu, FilterChain chaine) throws Exception {
        MockHttpServletRequest requete = new MockHttpServletRequest("GET", "/api/demandes");
        if (enTeteRecu != null) {
            requete.addHeader(FiltreRequestId.EN_TETE, enTeteRecu);
        }
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        filtre.doFilter(requete, reponse, chaine);
        return reponse;
    }

    @Test
    void enTeteAbsent_unUuidEstGenere() throws Exception {
        MockHttpServletResponse reponse = executer(null, (req, res) -> { });

        assertThat(reponse.getHeader(FiltreRequestId.EN_TETE)).matches(FORMAT_UUID);
    }

    @Test
    void enTeteValide_estRenvoyeALIdentique() throws Exception {
        MockHttpServletResponse reponse = executer("abc-123", (req, res) -> { });

        assertThat(reponse.getHeader(FiltreRequestId.EN_TETE)).isEqualTo("abc-123");
    }

    @Test
    void enTeteAvecRetourALaLigne_estRemplace() throws Exception {
        MockHttpServletResponse reponse = executer("abc\nFAUSSE LIGNE DE LOG", (req, res) -> { });

        assertThat(reponse.getHeader(FiltreRequestId.EN_TETE)).matches(FORMAT_UUID);
    }

    @Test
    void enTeteDePlusDe64Caracteres_estRemplace() throws Exception {
        MockHttpServletResponse reponse = executer("a".repeat(65), (req, res) -> { });

        assertThat(reponse.getHeader(FiltreRequestId.EN_TETE)).matches(FORMAT_UUID);
    }

    @Test
    void pendantLaChaine_leMdcPorteLId() throws Exception {
        AtomicReference<String> vuDansLaChaine = new AtomicReference<>();

        executer("abc-123", (req, res) -> vuDansLaChaine.set(MDC.get(FiltreRequestId.CLE_MDC)));

        assertThat(vuDansLaChaine.get()).isEqualTo("abc-123");
    }

    @Test
    void apresUneChaineQuiEchoue_leMdcEstVide() {
        FilterChain chaineEnEchec = (req, res) -> {
            MDC.put(FiltreRequestId.CLE_MDC_CORRELATION, "id-demande");
            throw new ServletException("panne simulee");
        };

        assertThatThrownBy(() -> executer("abc-123", chaineEnEchec)).isInstanceOf(ServletException.class);

        assertThat(MDC.get(FiltreRequestId.CLE_MDC)).isNull();
        assertThat(MDC.get(FiltreRequestId.CLE_MDC_CORRELATION)).isNull();
    }
}
