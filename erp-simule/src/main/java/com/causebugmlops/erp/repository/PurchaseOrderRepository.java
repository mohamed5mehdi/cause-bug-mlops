package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.commande.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
}
