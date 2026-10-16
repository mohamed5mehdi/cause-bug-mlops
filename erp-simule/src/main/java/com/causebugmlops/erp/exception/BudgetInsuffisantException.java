package com.causebugmlops.erp.exception;

import java.math.BigDecimal;

public class BudgetInsuffisantException extends RuntimeException {

    private final BigDecimal montantManquant;

    public BudgetInsuffisantException(String message) {
        super(message);
        this.montantManquant = null;
    }

    public BudgetInsuffisantException(String message, BigDecimal montantManquant) {
        super(message);
        this.montantManquant = montantManquant;
    }

    public BigDecimal getMontantManquant() {
        return montantManquant;
    }
}
