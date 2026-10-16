package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.ajustement.DemandeAjustement;
import com.causebugmlops.erp.domain.common.StatutAjustement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface DemandeAjustementRepository extends JpaRepository<DemandeAjustement, Long> {

    boolean existsByDemandeInterneIdAndStatutIn(UUID demandeId, Collection<StatutAjustement> statuts);
}
