package com.causebugmlops.erp.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;

/**
 * Ecrit les traces dans {@code erp.trace_execution}, dans une transaction
 * SEPAREE (REQUIRES_NEW) de toute transaction metier.
 * <p>
 * Une trace ne doit jamais faire echouer l'operation metier : une erreur
 * d'ecriture est journalisee puis ignoree.
 */
@Component
public class EnregistreurTraceExecution {

    private static final Logger LOG = LoggerFactory.getLogger(EnregistreurTraceExecution.class);

    private static final String INSERTION = """
            INSERT INTO erp.trace_execution
                (debut, duree_ms, request_id, business_correlation_id, composant, operation,
                 statut, exception_type, exception_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionSeparee;

    public EnregistreurTraceExecution(JdbcTemplate jdbc, PlatformTransactionManager gestionnaireTransactions) {
        this.jdbc = jdbc;
        this.transactionSeparee = new TransactionTemplate(gestionnaireTransactions);
        this.transactionSeparee.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Ecrit les traces d'une requete, dans l'ordre chronologique d'entree. */
    public void enregistrer(List<TraceExecution> traces) {
        if (traces.isEmpty()) {
            return;
        }
        List<TraceExecution> ordonnees = traces.stream()
                .sorted(Comparator.comparing(TraceExecution::debut))
                .toList();
        try {
            transactionSeparee.executeWithoutResult(statut -> jdbc.batchUpdate(INSERTION, ordonnees, ordonnees.size(),
                    (ps, t) -> {
                        ps.setTimestamp(1, Timestamp.from(t.debut()));
                        ps.setLong(2, t.dureeMs());
                        ps.setString(3, t.requestId());
                        ps.setObject(4, t.businessCorrelationId());
                        ps.setString(5, t.composant());
                        ps.setString(6, t.operation());
                        ps.setString(7, t.statut().name());
                        ps.setString(8, t.exceptionType());
                        ps.setString(9, t.exceptionMessage());
                    }));
        } catch (RuntimeException e) {
            LOG.warn("Traces d'execution non enregistrees ({} ligne(s)) : {}", ordonnees.size(), e.getMessage());
        }
    }
}
