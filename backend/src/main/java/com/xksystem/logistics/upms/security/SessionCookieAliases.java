package com.xksystem.logistics.upms.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.SessionTrackingMode;
import java.util.HashSet;
import java.util.Set;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.apache.tomcat.util.http.ServerCookie;
import org.apache.tomcat.util.http.ServerCookies;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/** Two contract names for the same container session, never separate credentials. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionCookieAliases {
    public static final String CANONICAL_NAME = "XKSESSION";
    public static final String ORDER_CONTRACT_NAME = "JSESSIONID";

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> sessionCookieAliasCustomizer(Environment environment) {
        if (!CANONICAL_NAME.equals(environment.getProperty("server.servlet.session.cookie.name"))) {
            throw new IllegalStateException("Session cookie aliases require the configured canonical cookie name");
        }
        var tracking = Binder.get(environment).bind("server.servlet.session.tracking-modes",
                Bindable.setOf(SessionTrackingMode.class)).orElse(Set.of());
        if (!tracking.equals(Set.of(SessionTrackingMode.COOKIE))) {
            throw new IllegalStateException("Session authentication requires cookie-only tracking");
        }
        return factory -> factory.addContextCustomizers(context -> {
            var processor = new AliasCookieProcessor();
            processor.setSameSiteCookies("lax");
            context.setCookieProcessor(processor);
        });
    }

    /** Called before authentication; ambiguous identities must not select either session. */
    public static boolean hasConflictingCookies(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) return false;
        var values = new HashSet<String>();
        for (var cookie : cookies) {
            if (CANONICAL_NAME.equals(cookie.getName()) || ORDER_CONTRACT_NAME.equals(cookie.getName())) {
                values.add(cookie.getValue());
            }
        }
        return values.size() > 1;
    }

    /** Mint/rotate both names only after the container has selected the current session. */
    public static void mirrorCurrentSession(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session == null || !request.isSecure()) return;
        // A JSESSIONID-only request resolves an existing session, so Tomcat does not
        // automatically mint its canonical cookie. Restore both names at auth endpoints.
        if (response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .noneMatch(header -> header.startsWith(CANONICAL_NAME + "="))) {
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(CANONICAL_NAME, session.getId()).build().toString());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(ORDER_CONTRACT_NAME, session.getId()).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /** Clear ambiguous client credentials without selecting or invalidating either server session. */
    public static void clearConflictingCookies(HttpServletResponse response) {
        for (String name : new String[] {CANONICAL_NAME, ORDER_CONTRACT_NAME}) {
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(name, "").maxAge(0).build().toString());
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private static ResponseCookie.ResponseCookieBuilder sessionCookie(String name, String value) {
        return ResponseCookie.from(name, value).path("/").secure(true).httpOnly(true).sameSite("Lax");
    }

    /** Runs before Tomcat resolves HttpSession; a servlet getCookies wrapper is too late. */
    static final class AliasCookieProcessor extends Rfc6265CookieProcessor {
        @Override
        public void parseCookieHeader(MimeHeaders headers, ServerCookies cookies) {
            super.parseCookieHeader(headers, cookies);
            boolean canonicalPresent = false;
            ServerCookie alias = null;
            int count = cookies.getCookieCount();
            for (int index = 0; index < count; index++) {
                var cookie = cookies.getCookie(index);
                String name = cookie.getName().toString();
                if (CANONICAL_NAME.equals(name)) canonicalPresent = true;
                if (ORDER_CONTRACT_NAME.equals(name)) alias = cookie;
            }
            if (!canonicalPresent && alias != null) {
                String value = alias.getValue().toString();
                var canonical = cookies.addCookie();
                canonical.getName().setString(CANONICAL_NAME);
                canonical.getValue().setString(value);
            }
        }
    }
}
