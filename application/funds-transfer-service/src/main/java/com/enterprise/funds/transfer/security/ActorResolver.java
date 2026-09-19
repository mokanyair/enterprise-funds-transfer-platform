package com.enterprise.funds.transfer.security;

import com.enterprise.funds.transfer.domain.Actor;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.persistence.UserRepository;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the caller as an Actor. Identity comes only from the verified token subject, mapped through APP_USERS;
 * there is no client-supplied user id anywhere. A valid token for an unknown or disabled user is treated as
 * unauthenticated, so the API does not reveal which subjects exist.
 */
@Component
public class ActorResolver implements HandlerMethodArgumentResolver {

    private final UserRepository users;

    public ActorResolver(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || jwt.getToken().getSubject() == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return users.findActiveBySubject(jwt.getToken().getSubject())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }
}
