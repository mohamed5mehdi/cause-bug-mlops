package com.causebugmlops.erp.api.erreur;

import com.causebugmlops.erp.api.tracabilite.FiltreRequestId;
import com.causebugmlops.erp.exception.BudgetInsuffisantException;
import com.causebugmlops.erp.exception.RessourceIntrouvableException;
import com.causebugmlops.erp.exception.StockInsuffisantException;
import com.causebugmlops.erp.exception.TransitionStatutInvalideException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Traduit les exceptions en reponses HTTP lisibles au format RFC 9457 (ProblemDetail).
 * <p>
 * Correspondance (ADR-008) :
 * <ul>
 *   <li>400 : requete mal formee (champ invalide, en-tete manquant, UUID illisible) -
 *       exceptions Spring MVC traitees par {@link ResponseEntityExceptionHandler}</li>
 *   <li>404 : {@link RessourceIntrouvableException}</li>
 *   <li>409 : {@link TransitionStatutInvalideException}, conflit de version (@Version)</li>
 *   <li>422 : {@link BudgetInsuffisantException}, {@link StockInsuffisantException}</li>
 *   <li>503 : verrou pessimiste non obtenu (reessayable, en-tete Retry-After)</li>
 *   <li>500 : tout le reste, dont EquationBudgetaireException et les IllegalStateException
 *       restantes - un defaut, jamais un refus metier, donc un signal pour le diagnostic</li>
 * </ul>
 * Chaque reponse porte {@code code} (stable, lisible par une machine), {@code requestId}
 * et, si connu, {@code businessCorrelationId}.
 */
@RestControllerAdvice
public class GestionnaireErreursApi extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GestionnaireErreursApi.class);
    static final String DELAI_REESSAI_SECONDES = "1";

    @ExceptionHandler(RessourceIntrouvableException.class)
    public ResponseEntity<ProblemDetail> ressourceIntrouvable(RessourceIntrouvableException ex) {
        ProblemDetail pb = probleme(HttpStatus.NOT_FOUND, "Ressource introuvable", ex.getMessage(), "RESSOURCE_INTROUVABLE");
        pb.setProperty("ressource", ex.getRessource());
        pb.setProperty("identifiant", ex.getIdentifiant());
        return refusMetier(pb, ex, null);
    }

    @ExceptionHandler(TransitionStatutInvalideException.class)
    public ResponseEntity<ProblemDetail> transitionInvalide(TransitionStatutInvalideException ex) {
        ProblemDetail pb = probleme(HttpStatus.CONFLICT, "Action hors sequence", ex.getMessage(), "TRANSITION_STATUT_INVALIDE");
        if (ex.getStatutActuel() != null) {
            pb.setProperty("statutActuel", ex.getStatutActuel().name());
        }
        return refusMetier(pb, ex, null);
    }

    @ExceptionHandler(BudgetInsuffisantException.class)
    public ResponseEntity<ProblemDetail> budgetInsuffisant(BudgetInsuffisantException ex) {
        ProblemDetail pb = probleme(HttpStatus.UNPROCESSABLE_ENTITY, "Budget insuffisant", ex.getMessage(), "BUDGET_INSUFFISANT");
        if (ex.getMontantManquant() != null) {
            pb.setProperty("montantManquant", ex.getMontantManquant());
        }
        return refusMetier(pb, ex, null);
    }

    @ExceptionHandler(StockInsuffisantException.class)
    public ResponseEntity<ProblemDetail> stockInsuffisant(StockInsuffisantException ex) {
        ProblemDetail pb = probleme(HttpStatus.UNPROCESSABLE_ENTITY, "Stock insuffisant", ex.getMessage(), "STOCK_INSUFFISANT");
        return refusMetier(pb, ex, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> modificationConcurrente(OptimisticLockingFailureException ex) {
        ProblemDetail pb = probleme(HttpStatus.CONFLICT, "Modification concurrente",
                "La ressource a ete modifiee par une autre requete. Relisez-la puis recommencez.", "MODIFICATION_CONCURRENTE");
        return refusMetier(pb, ex, null);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> ressourceVerrouillee(PessimisticLockingFailureException ex) {
        ProblemDetail pb = probleme(HttpStatus.SERVICE_UNAVAILABLE, "Ressource verrouillee",
                "Une autre operation utilise cette ressource. Reessayez dans un instant.", "RESSOURCE_VERROUILLEE");
        return refusMetier(pb, ex, DELAI_REESSAI_SECONDES);
    }

    /** Filet de securite : tout ce qui n'est pas un refus metier connu est un defaut. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> erreurInterne(Exception ex) {
        ProblemDetail pb = probleme(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne",
                "Une erreur inattendue est survenue. Communiquez le requestId pour l'analyse.", "ERREUR_INTERNE");
        // Message technique jamais renvoye au client ; trace complete dans les logs.
        LOG.error("Erreur interne non geree : {}", ex.getMessage(), ex);
        return construire(pb, null);
    }

    /** 400 detaille : la liste des champs invalides, avec leur message. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail pb = ex.getBody();
        pb.setDetail("La requete contient des champs invalides.");
        List<Map<String, String>> erreurs = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("champ", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        pb.setProperty("erreurs", erreurs);
        return handleExceptionInternal(ex, pb, headers, status, request);
    }

    /** Point de passage de toutes les exceptions Spring MVC : ajoute code et identifiants. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> reponse = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        boolean erreurClient = statusCode.is4xxClientError();
        if (reponse != null && reponse.getBody() instanceof ProblemDetail pb) {
            pb.setProperty("code", erreurClient ? "REQUETE_INVALIDE" : "ERREUR_INTERNE");
            enrichir(pb);
        }
        if (erreurClient) {
            LOG.warn("Requete refusee ({}) : {}", statusCode.value(), ex.getMessage());
        } else {
            LOG.error("Erreur technique ({}) : {}", statusCode.value(), ex.getMessage(), ex);
        }
        return reponse;
    }

    private static ProblemDetail probleme(HttpStatus statut, String titre, String detail, String code) {
        ProblemDetail pb = ProblemDetail.forStatusAndDetail(statut, detail);
        pb.setTitle(titre);
        pb.setProperty("code", code);
        return pb;
    }

    private static ResponseEntity<ProblemDetail> refusMetier(ProblemDetail pb, Exception ex, String retryAfter) {
        LOG.warn("Refus metier ({}) {} : {}", pb.getStatus(), pb.getProperties().get("code"), ex.getMessage());
        return construire(pb, retryAfter);
    }

    private static ResponseEntity<ProblemDetail> construire(ProblemDetail pb, String retryAfter) {
        enrichir(pb);
        ResponseEntity.BodyBuilder reponse = ResponseEntity.status(pb.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfter != null) {
            reponse.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return reponse.body(pb);
    }

    private static void enrichir(ProblemDetail pb) {
        String requestId = MDC.get(FiltreRequestId.CLE_MDC);
        if (requestId != null) {
            pb.setProperty("requestId", requestId);
        }
        String correlationId = MDC.get(FiltreRequestId.CLE_MDC_CORRELATION);
        if (correlationId != null) {
            pb.setProperty("businessCorrelationId", correlationId);
        }
    }
}
