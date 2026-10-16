package com.causebugmlops.erp.exception;

import java.math.BigDecimal;

public class EquationBudgetaireException extends RuntimeException {

    private final String exercice;
    private final BigDecimal budgetInitial;
    private final BigDecimal budgetEngage;
    private final BigDecimal budgetRestant;

    public EquationBudgetaireException(String exercice, BigDecimal budgetInitial, BigDecimal budgetEngage, BigDecimal budgetRestant) {
        super(String.format("Rupture de l'equation budgetaire pour l'exercice %s: budgetInitial=%s, budgetEngage=%s, budgetRestant=%s",
                exercice, budgetInitial, budgetEngage, budgetRestant));
        this.exercice = exercice;
        this.budgetInitial = budgetInitial;
        this.budgetEngage = budgetEngage;
        this.budgetRestant = budgetRestant;
    }

    public String getExercice() {
        return exercice;
    }

    public BigDecimal getBudgetInitial() {
        return budgetInitial;
    }

    public BigDecimal getBudgetEngage() {
        return budgetEngage;
    }

    public BigDecimal getBudgetRestant() {
        return budgetRestant;
    }
}
