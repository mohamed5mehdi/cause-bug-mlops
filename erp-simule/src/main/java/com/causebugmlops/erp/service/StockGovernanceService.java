package com.causebugmlops.erp.service;

import com.causebugmlops.erp.domain.article.Article;
import com.causebugmlops.erp.exception.StockInsuffisantException;
import com.causebugmlops.erp.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockGovernanceService {

    private final ArticleRepository articleRepository;

    @Transactional
    public void verifierStock(Long articleId, Integer quantiteDemandee) {
        Article article = articleRepository.findByIdForUpdate(articleId)
                .orElseThrow(() -> new IllegalArgumentException("Article introuvable: " + articleId));

        if (article.getQuantiteEnStock() < quantiteDemandee) {
            throw new StockInsuffisantException("Stock insuffisant");
        }

        article.setQuantiteEnStock(article.getQuantiteEnStock() - quantiteDemandee);
        articleRepository.save(article);
    }
}
