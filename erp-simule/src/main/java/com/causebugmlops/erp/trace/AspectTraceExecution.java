package com.causebugmlops.erp.trace;

import com.causebugmlops.erp.api.tracabilite.FiltreRequestId;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Trace chaque appel de methode publique des services metier (succes ou echec).
 * <p>
 * Deux choix structurants (ADR-010) :
 * <ul>
 *   <li><b>Ordre le plus externe</b> ({@link Ordered#HIGHEST_PRECEDENCE}) : l'aspect
 *       entoure le proxy transactionnel. Un echec au COMMIT (contrainte violee au
 *       flush) est donc vu comme un ECHEC, pas comme un faux SUCCES.</li>
 *   <li><b>Tampon par requete</b> : les appels imbriques (ex. valoriser ->
 *       verifierEtEngager) sont gardes en memoire et ecrits en une fois quand
 *       l'appel le plus externe se termine. Aucune deuxieme connexion n'est donc
 *       ouverte pendant que la transaction metier tient la sienne (pas de
 *       famine du pool de connexions sous charge).</li>
 * </ul>
 * Limite connue : une trace en tampon est perdue si la JVM s'arrete brutalement
 * avant la fin de la requete.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AspectTraceExecution {

    private static final int LONGUEUR_MAX_MESSAGE = 1000;
    private static final ThreadLocal<List<TraceExecution>> TAMPON = new ThreadLocal<>();

    private final EnregistreurTraceExecution enregistreur;

    public AspectTraceExecution(EnregistreurTraceExecution enregistreur) {
        this.enregistreur = enregistreur;
    }

    @Around("execution(public * com.causebugmlops.erp.service..*(..))")
    public Object tracer(ProceedingJoinPoint appel) throws Throwable {
        boolean appelRacine = TAMPON.get() == null;
        if (appelRacine) {
            TAMPON.set(new ArrayList<>());
        }
        Instant debut = Instant.now();
        long t0 = System.nanoTime();
        Throwable erreur = null;
        try {
            return appel.proceed();
        } catch (Throwable t) {
            erreur = t;
            throw t;
        } finally {
            long dureeMs = (System.nanoTime() - t0) / 1_000_000;
            TAMPON.get().add(construire(appel, debut, dureeMs, erreur));
            if (appelRacine) {
                List<TraceExecution> traces = TAMPON.get();
                TAMPON.remove();
                enregistreur.enregistrer(traces);
            }
        }
    }

    private static TraceExecution construire(ProceedingJoinPoint appel, Instant debut, long dureeMs, Throwable erreur) {
        return new TraceExecution(
                debut,
                dureeMs,
                MDC.get(FiltreRequestId.CLE_MDC),
                correlation(MDC.get(FiltreRequestId.CLE_MDC_CORRELATION)),
                appel.getSignature().getDeclaringType().getSimpleName(),
                appel.getSignature().getName(),
                erreur == null ? TraceExecution.Statut.SUCCES : TraceExecution.Statut.ECHEC,
                erreur == null ? null : erreur.getClass().getName(),
                erreur == null ? null : tronquer(erreur.getMessage()));
    }

    /** Le MDC ne contient qu'un UUID valide (intercepteur) ; prudence malgre tout. */
    private static UUID correlation(String valeur) {
        if (valeur == null) {
            return null;
        }
        try {
            return UUID.fromString(valeur);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String tronquer(String message) {
        if (message == null || message.length() <= LONGUEUR_MAX_MESSAGE) {
            return message;
        }
        return message.substring(0, LONGUEUR_MAX_MESSAGE);
    }
}
