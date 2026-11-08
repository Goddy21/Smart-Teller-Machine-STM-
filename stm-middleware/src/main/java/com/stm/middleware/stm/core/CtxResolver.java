package com.stm.middleware.stm.core;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Supplies {@link Ctx} to Gateway controller methods. X-Session-Id is required unless the method is @SessionOptional. */
@Configuration
public class CtxResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {
    private final SessionStore sessions;
    private final CustomerTokens tokens;

    public CtxResolver(SessionStore sessions, CustomerTokens tokens) {
        this.sessions = sessions;
        this.tokens = tokens;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Ctx.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest web,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest req = web.getNativeRequest(HttpServletRequest.class);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof DevicePrincipal device)) throw Errors.deviceUnauthorized();
        String sid = req.getHeader("X-Session-Id");
        boolean optional = parameter.hasMethodAnnotation(SessionOptional.class);
        boolean allowEnded = parameter.hasMethodAnnotation(AllowEndedSession.class);
        StmSession session = null;
        if (sid != null && !sid.isBlank()) session = sessions.require(sid.trim(), device.terminalId(), allowEnded);
        else if (!optional) throw Errors.missingHeader("X-Session-Id");
        return new Ctx(device, session, req, tokens);
    }
}
