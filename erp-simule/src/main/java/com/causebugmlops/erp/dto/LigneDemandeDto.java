package com.causebugmlops.erp.dto;

import java.math.BigDecimal;

public record LigneDemandeDto(
        Long id,
        Long articleId,
        String designationLibre,
        String specification,
        Integer quantite,
        String justificationLigne,
        BigDecimal prixUnitaire
) {}
