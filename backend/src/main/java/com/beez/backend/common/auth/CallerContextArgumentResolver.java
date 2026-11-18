package com.beez.backend.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 컨트롤러 메서드 인자에 {@link CallerContext} 가 있으면 {@link CallerResolver} 로 채워 준다.
 * 컨트롤러는 "누가 불렀나"를 직접 꺼내지 않고 인자로 받기만 한다.
 */
public class CallerContextArgumentResolver implements HandlerMethodArgumentResolver {

    private final CallerResolver callerResolver;

    public CallerContextArgumentResolver(CallerResolver callerResolver) {
        this.callerResolver = callerResolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallerContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return callerResolver.resolve(request);
    }
}
