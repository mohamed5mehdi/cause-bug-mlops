package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.demande.LigneDemande;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LigneDemandeRepository extends JpaRepository<LigneDemande, Long> {
}
