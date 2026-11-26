package com.xksystem.logistics.tms.execution;

import com.xksystem.logistics.tms.internal.ExecutionService;
import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tms/my-tasks")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ExecutionController {
    private final ExecutionService service;
    public ExecutionController(ExecutionService service){this.service=service;}
    @GetMapping(value="/{assignmentId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<ExecutionDtos.Detail> detail(Authentication authentication,@PathVariable String assignmentId,
            @RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        if(!request.getMethod().equals("GET"))throw new ExecutionFailure(405,"E0405","不支援此操作方法",null);
        noQuery(query);
        return ApiEnvelope.success(service.detail(principal(authentication),ExecutionValidation.assignmentId(assignmentId),request.getSession(false)));
    }
    @PostMapping(value="/{assignmentId}/depart",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<ExecutionDtos.DepartureResult>> depart(Authentication authentication,
            @PathVariable String assignmentId,@RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query,
            HttpServletRequest request) {
        noQuery(query);var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1)throw ExecutionFailure.invalid("Idempotency-Key","請提供單一有效重送識別");
        String key=ExecutionValidation.key(keys.getFirst());
        var result=service.depart(principal(authentication),ExecutionValidation.assignmentId(assignmentId),body,key,request.getSession(false));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(ApiEnvelope.success(result.data()));
    }
    @RequestMapping({"/{assignmentId}","/{assignmentId}/depart"})
    public void unsupported(HttpServletRequest request) {
        boolean command=request.getServletPath().endsWith("/depart");
        String expected=command?"POST":"GET";
        if(!request.getMethod().equals(expected))throw new ExecutionFailure(405,"E0405","不支援此操作方法",null);
        if(command) {
            MediaType contentType;
            try { contentType=MediaType.parseMediaType(request.getContentType()==null?"application/octet-stream":request.getContentType()); }
            catch(IllegalArgumentException invalid){throw new ExecutionFailure(415,"E0415","請使用 JSON 格式提交資料",null);}
            if(!MediaType.APPLICATION_JSON.isCompatibleWith(contentType))throw new ExecutionFailure(415,"E0415","請使用 JSON 格式提交資料",null);
        }
        throw ExecutionFailure.invalid("request","請依契約使用 JSON 請求與回應格式");
    }
    private static void noQuery(MultiValueMap<String,String> query) {
        if(!query.isEmpty())throw ExecutionFailure.invalid("query","此操作不接受查詢參數");
    }
    private static SessionPrincipal principal(Authentication authentication) {
        if(authentication==null||!(authentication.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();
        return principal;
    }
}
