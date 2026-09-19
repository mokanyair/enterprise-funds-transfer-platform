package com.enterprise.funds.transfer.web;

import com.enterprise.funds.transfer.security.ActorResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ActorResolver actorResolver;

    public WebConfig(ActorResolver actorResolver) {
        this.actorResolver = actorResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(actorResolver);
    }
}
