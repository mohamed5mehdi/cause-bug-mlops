package com.causebugmlops.erp.trace;

import java.time.Instant;
import java.util.UUID;

/**
 * Un appel de methode d'un service metier, tel qu'il sera ecrit dans
 * {@code erp.trace_execution}.
 *
 * @param debut                 instant d'entree dans la methode (ordre chronologique)
 * @param dureeMs               duree de l'appel
 * @param requestId             identifiant technique de la requete HTTP (peut etre null)
 * @param businessCorrelationId identifiant de la demande d'achat (null si inconnu)
 * @param composant             nom simple de la classe de service
 * @param operation             nom de la methode
 * @param statut                SUCCES ou ECHEC
 * @param exceptionType         nom complet de l'exception (null si SUCCES)
 * @param exceptionMessage      message tronque a 1000 caracteres (null si SUCCES)
 */
public record TraceExecution(
        Instant debut,
        long dureeMs,
        String requestId,
        UUID businessCorrelationId,
        String composant,
        String operation,
        Statut statut,
        String exceptionType,
        String exceptionMessage
) {

    public enum Statut { SUCCES, ECHEC }
}
