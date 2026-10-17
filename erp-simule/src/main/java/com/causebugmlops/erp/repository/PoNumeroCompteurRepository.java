package com.causebugmlops.erp.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acces a la fonction SQL {@code erp.po_numero_compteur()} (migration V2), qui
 * produit un numero de PO unique au format {@code PO-AAAAMMJJ-NNNN} a partir de
 * la sequence {@code erp.po_seq}.
 * <p>
 * Implemente via {@link JdbcTemplate} et non comme repository Spring Data : la
 * fonction ne correspond a aucune entite JPA, et l'ancienne declaration
 * {@code Repository<Object, Long>} empechait le demarrage du contexte
 * ("Not a managed type: class java.lang.Object", cf. ADR-003).
 * <p>
 * L'appel s'execute dans la transaction de l'appelant ({@code JpaTransactionManager}
 * expose la connexion JDBC de la transaction en cours a {@code JdbcTemplate}).
 * Une sequence PostgreSQL n'est jamais annulee par un rollback : un numero
 * consomme puis annule laisse un trou dans la numerotation, comportement
 * attendu pour un compteur de documents.
 */
@Repository
@RequiredArgsConstructor
public class PoNumeroCompteurRepository {

    private static final String SQL_PROCHAIN_NUMERO = "SELECT erp.po_numero_compteur()";

    private final JdbcTemplate jdbcTemplate;

    /**
     * @return le prochain numero de PO, jamais {@code null}
     * @throws IllegalStateException si la fonction SQL ne renvoie aucune valeur
     */
    public String getNextPoNumero() {
        String numero = jdbcTemplate.queryForObject(SQL_PROCHAIN_NUMERO, String.class);
        if (numero == null) {
            throw new IllegalStateException("erp.po_numero_compteur() n'a renvoye aucun numero");
        }
        return numero;
    }
}
