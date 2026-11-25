package com.xksystem.logistics.upms.security;

import com.xksystem.logistics.upms.internal.UpmsService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class UpmsSecurityConfiguration {
    @Bean
    public HttpSessionCsrfTokenRepository csrfTokens() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    public SecurityFilterChain upmsSecurity(HttpSecurity http, UpmsService service, Clock clock,
            SecurityResponses responses, HttpSessionCsrfTokenRepository csrfTokens, Environment environment) throws Exception {
        // No profile or environment flag can turn off these invariants in a production artifact.
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)
                || !environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class, false)
                || !"lax".equalsIgnoreCase(environment.getProperty("server.servlet.session.cookie.same-site"))
                || !"/".equals(environment.getProperty("server.servlet.session.cookie.path")))
            throw new IllegalStateException("UPMS requires Secure, HttpOnly, SameSite=Lax, Path=/ session cookies");
        http.formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable());
        http.requestCache(cache -> cache.disable());
        http.securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> responses.error(response, 401, "無法登入或登入已失效，請確認資料後重新登入"))
                .accessDeniedHandler((request, response, exception) -> responses.error(response, 403, "無權執行此操作，請確認權限或重新整理後再試")));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/v1/status", "/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/upms/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/adm/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/auth/**").authenticated()
                .anyRequest().permitAll());
        http.addFilterBefore(new UpmsAuthenticationFilter(service, clock, responses), AuthorizationFilter.class);
        http.addFilterBefore(new UpmsAccessFilter(responses), AuthorizationFilter.class);
        return http.build();
    }
}
