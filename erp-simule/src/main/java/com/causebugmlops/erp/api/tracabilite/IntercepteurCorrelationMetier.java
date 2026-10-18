package com.causebugmlops.erp.api.tracabilite;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Place l'identifiant de la demande d'achat dans le MDC (businessCorrelationId)
 * des que la route le porte ({@code /api/demandes/{id}/...}).
 * <p>
 * Le filtre {@link FiltreRequestId} pose l'identifiant technique (une requete) ;
 * cet intercepteur pose l'identifiant metier (un dossier, suivi sur plusieurs
 * requetes). Il s'execute apres la resolution de la route, seul moment ou la
 * variable {id} est connue. Le nettoyage du MDC reste a la charge du filtre.
 * <p>
 * Seul un UUID bien forme est retenu, pour qu'aucune saisie arbitraire de l'URL
 * n'arrive dans les logs.
 */
@Component
public class IntercepteurCorrelationMetier implements HandlerInterceptor {

    static final String VARIABLE_ROUTE = "id";
    private static final Pattern FORMAT_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> route
                && route.get(VARIABLE_ROUTE) instanceof String id
                && FORMAT_UUID.matcher(id).matches()) {
            MDC.put(FiltreRequestId.CLE_MDC_CORRELATION, id);
        }
        return true;
    }
}
