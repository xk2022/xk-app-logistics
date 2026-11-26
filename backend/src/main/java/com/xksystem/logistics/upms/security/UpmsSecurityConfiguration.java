package com.xksystem.logistics.upms.security;

import com.xksystem.logistics.upms.internal.UpmsService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class UpmsSecurityConfiguration {
    @Bean
    public WebSecurityCustomizer rejectedRequests(SecurityResponses responses) {
        return web->web.requestRejectedHandler((request,response,exception)->responses.error(request,response,400,"VALIDATION_ERROR","請確認請求路徑與格式"));
    }
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
        // Authentication is rebuilt from our validated HttpSession on each request.
        // Only AuthController runs login/CSRF rotation; automatic "new authentication"
        // strategies would rotate the session (and invalidate the alias) on every request.
        // IF_REQUIRED is already the default. Explicitly setting it conflicts with
        // Security 7's explicit-authentication-strategy mode and reinstalls implicit handling.
        http.sessionManagement(session -> session.requireExplicitAuthenticationStrategy(true));
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfRequestHandler(request->request.getMethod().equals("POST")
                        &&(request.getServletPath().matches("/api/v1/tms/my-tasks/[^/]+/evidence")
                        ||request.getServletPath().matches("/api/v1/tms/my-tasks/[^/]+/exceptions(?:/[^/]+/supplement-requests/[^/]+/(?:evidence|submit))?")
                        ||request.getServletPath().matches("/api/v1/tms/exceptions/[^/]+/(?:supplement-requests|resolve)")
                        ||request.getServletPath().matches("/api/v1/tms/my-tasks/[^/]+/complete")
                        ||request.getServletPath().matches("/api/v1/tms/completions/[^/]+/confirm")
                        ||request.getServletPath().matches("/api/v1/orders/[^/]+/close")))));
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> responses.error(request,response,401,"UNAUTHENTICATED","無法登入或登入已失效，請確認資料後重新登入"))
                .accessDeniedHandler((request, response, exception) -> responses.error(request,response,403,
                        exception instanceof CsrfException?"CSRF_INVALID":"FORBIDDEN","無權執行此操作，請確認權限或重新整理後再試")));
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/v1/status", "/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/upms/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/adm/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/crm/**").hasAnyRole("ADMIN","DISPATCHER")
                .requestMatchers(HttpMethod.GET,"/api/v1/fms/drivers/me").hasRole("DRIVER")
                .requestMatchers("/api/v1/fms/**").hasAnyRole("ADMIN","DISPATCHER")
                .requestMatchers("/api/v1/orders","/api/v1/orders/**").hasAnyRole("ADMIN","DISPATCHER")
                .requestMatchers(HttpMethod.GET,"/api/v1/tms/my-tasks").hasRole("DRIVER")
                .requestMatchers("/api/v1/tms/my-tasks/*","/api/v1/tms/my-tasks/*/depart").hasRole("DRIVER")
                .requestMatchers("/api/v1/tms/my-tasks/*/progress-policy","/api/v1/tms/my-tasks/*/progress",
                        "/api/v1/tms/my-tasks/*/evidence","/api/v1/tms/my-tasks/*/evidence/*/content").hasRole("DRIVER")
                .requestMatchers("/api/v1/tms/my-tasks/*/exceptions","/api/v1/tms/my-tasks/*/exceptions/*",
                        "/api/v1/tms/my-tasks/*/exceptions/*/history",
                        "/api/v1/tms/my-tasks/*/exceptions/*/supplement-requests/*/evidence",
                        "/api/v1/tms/my-tasks/*/exceptions/*/supplement-requests/*/submit").hasRole("DRIVER")
                .requestMatchers("/api/v1/tms/my-tasks/*/completion","/api/v1/tms/my-tasks/*/completion-policy",
                        "/api/v1/tms/my-tasks/*/complete").hasRole("DRIVER")
                .requestMatchers("/api/v1/tms","/api/v1/tms/**").hasAnyRole("ADMIN","DISPATCHER")
                .requestMatchers("/api/v1/auth/**").authenticated()
                .anyRequest().permitAll());
        http.addFilterBefore(new UpmsAuthenticationFilter(service, clock, responses), CsrfFilter.class);
        http.addFilterBefore(new UpmsAccessFilter(responses), AuthorizationFilter.class);
        return http.build();
    }
}
