package com.beez.backend.common.auth;

import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** {@link CallerContextArgumentResolver} 등록. 기존 WebConfig(CORS)와 분리해 둔다. */
@Configuration
public class CallerWebConfig implements WebMvcConfigurer {

    static {
        // Swagger 문서에서 CallerContext 인자를 쿼리 파라미터로 오해하지 않게 제외한다
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CallerContext.class);
    }

    private final CallerResolver callerResolver;

    public CallerWebConfig(CallerResolver callerResolver) {
        this.callerResolver = callerResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerContextArgumentResolver(callerResolver));
    }
}
