package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;

import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

class TmsErrorsTest {
    @AfterEach void clearSecurity(){SecurityContextHolder.clearContext();}

    @Test void authorizationReasonDoesNotEchoSourceExceptionData() {
        var errors=new TmsErrors();
        var forbidden=errors.source(new UpmsException(403,"無權操作",Map.of("privateSource","not-for-the-client")));
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(forbidden.getBody().code()).isEqualTo("E0403");
        assertThat(forbidden.getBody().data()).isEqualTo(Map.of("reason","FORBIDDEN"));
        var anonymous=errors.source(new UpmsException(401,"請登入",Map.of("privateSource","not-for-the-client")));
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(anonymous.getBody().data()).isNull();
    }

    @Test void csrfReasonRequiresAnAuthenticatedPrincipalAndRejectedPathsKeepTheValidationContract() throws Exception {
        var json=JsonMapper.builder().build();var errors=new TmsSecurityErrors(json);
        var request=new MockHttpServletRequest();request.setServletPath("/api/v1/tms/tasks");request.setRequestURI("/api/v1/tms/tasks");
        var principal=new SessionPrincipal(UUID.randomUUID(),0,Instant.now(),Instant.now());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
        var csrf=new MockHttpServletResponse();errors.render(request,csrf,403,"CSRF_INVALID","安全驗證未通過");
        assertThat(csrf.getStatus()).isEqualTo(403);
        assertThat(json.readTree(csrf.getContentAsString()).get("data").get("reason").asString()).isEqualTo("CSRF_INVALID");
        SecurityContextHolder.clearContext();
        var anonymous=new MockHttpServletResponse();errors.render(request,anonymous,403,"CSRF_INVALID","請登入");
        assertThat(anonymous.getStatus()).isEqualTo(401);
        assertThat(json.readTree(anonymous.getContentAsString()).get("data").isNull()).isTrue();
        request.setServletPath("");request.setRequestURI("/api/v1/tms/invalid-path");
        assertThat(errors.supports(request)).isTrue();
        var invalid=new MockHttpServletResponse();errors.render(request,invalid,400,"VALIDATION_ERROR","路徑格式不合法");
        assertThat(invalid.getStatus()).isEqualTo(400);
        var data=json.readTree(invalid.getContentAsString()).get("data");
        assertThat(data.get("reason").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(data.get("fields").get("request").asString()).isNotBlank();
    }
}
