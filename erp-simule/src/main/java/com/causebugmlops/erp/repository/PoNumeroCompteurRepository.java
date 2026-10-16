package com.causebugmlops.erp.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PoNumeroCompteurRepository extends Repository<Object, Long> {

    @Query(value = "SELECT erp.po_numero_compteur()", nativeQuery = true)
    String getNextPoNumero();
}
