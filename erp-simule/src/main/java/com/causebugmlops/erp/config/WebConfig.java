package com.causebugmlops.erp.config;

import com.causebugmlops.erp.api.tracabilite.IntercepteurCorrelationMetier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration Spring MVC : branche l'intercepteur de correlation metier sur les
 * routes du workflow Achat Interne.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final IntercepteurCorrelationMetier intercepteurCorrelationMetier;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(intercepteurCorrelationMetier).addPathPatterns("/api/demandes/**");
    }
}
