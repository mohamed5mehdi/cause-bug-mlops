package com.causebugmlops.erp.api.tracabilite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Attribue a chaque requete HTTP un identifiant technique (requestId),
 * le place dans le MDC pour les logs et le renvoie au client.
 * <p>
 * Un X-Request-Id recu n'est conserve que s'il respecte un format strict,
 * pour empecher l'injection de faux contenus dans les logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltreRequestId extends OncePerRequestFilter {

    public static final String EN_TETE = "X-Request-Id";
    public static final String CLE_MDC = "requestId";
    public static final String CLE_MDC_CORRELATION = "businessCorrelationId";
    private static final Pattern FORMAT_VALIDE = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String recu = request.getHeader(EN_TETE);

        String requestId;
        if (recu != null && FORMAT_VALIDE.matcher(recu).matches()) {
            requestId = recu;
        } else {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(CLE_MDC, requestId);
        response.setHeader(EN_TETE, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CLE_MDC);
            MDC.remove(CLE_MDC_CORRELATION);
        }
    }
}