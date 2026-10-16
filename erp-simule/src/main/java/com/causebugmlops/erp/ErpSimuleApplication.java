package com.causebugmlops.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Point d'entree du module erp-simule.
 * <p>
 * Ce service ne connait jamais l'existence de aiops-engine : il produit un flux
 * transactionnel realiste (et parfois defaillant via le module de fault injection,
 * introduit en Phase 3) sans aucune dependance vers la couche de diagnostic.
 */
@SpringBootApplication
@EnableJpaAuditing
public class ErpSimuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpSimuleApplication.class, args);
    }
}
