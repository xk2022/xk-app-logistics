package com.xksystem.logistics.tms.completion;

import com.xksystem.logistics.tms.internal.CompletionService;
import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tms/my-tasks/{assignmentId}")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class CompletionController {
    private final CompletionService service;
    public CompletionController(CompletionService service){this.service=service;}

    @GetMapping(value="/completion",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> detail(Authentication authentication,@PathVariable String assignmentId,HttpServletRequest request) {
        getOnly(request);noQuery(request);
        return json(service.detail(principal(authentication),id(assignmentId),request.getSession(false)));
    }
    @GetMapping(value="/completion-policy",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> policy(Authentication authentication,@PathVariable String assignmentId,HttpServletRequest request) {
        getOnly(request);noQuery(request);
        return json(service.policy(principal(authentication),id(assignmentId),request.getSession(false)));
    }
    @PostMapping(value="/complete",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> complete(Authentication authentication,@PathVariable String assignmentId,HttpServletRequest request)throws IOException {
        noQuery(request);var actor=principal(authentication);var assignment=id(assignmentId);
        String key=CompletionValidation.key(single(request,"Idempotency-Key",true));
        var originalSession=request.getSession(false);
        media(request);
        byte[] raw=CompletionValidation.bounded(request.getInputStream(),request.getContentLengthLong());
        var result=service.complete(actor,assignment,raw,key,originalSession);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private")
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(ApiEnvelope.success(result.data()));
    }
    @RequestMapping({"/completion","/completion-policy","/complete"})
    public void unsupported(HttpServletRequest request) {
        boolean complete=request.getServletPath().endsWith("/complete");
        if(complete?!request.getMethod().equals("POST"):!request.getMethod().equals("GET"))throw method();
        if(complete)media(request);
        throw CompletionFailure.invalid("request","請依契約提供正確請求與回應格式");
    }
    private static void media(HttpServletRequest request) {
        String value=single(request,"Content-Type",false);
        if(value==null)throw mediaFailure();
        try {
            var actual=MediaType.parseMediaType(value);
            if(!actual.getType().equalsIgnoreCase("application")||!actual.getSubtype().equalsIgnoreCase("json"))throw mediaFailure();
            for(String parameter:actual.getParameters().keySet())if(!parameter.equalsIgnoreCase("charset"))throw mediaFailure();
            if(actual.getCharset()!=null&&!actual.getCharset().equals(StandardCharsets.UTF_8))throw mediaFailure();
        } catch(IllegalArgumentException invalid){throw mediaFailure();}
        String encoding=single(request,"Content-Encoding",false);
        if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw mediaFailure();
    }
    private static String single(HttpServletRequest request,String name,boolean required) {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()>1||required&&values.size()!=1)throw CompletionFailure.invalid(name,"請提供單一有效欄位");
        return values.isEmpty()?null:values.getFirst();
    }
    private static UUID id(String value){return CompletionValidation.uuid(value,"assignmentId");}
    private static void noQuery(HttpServletRequest request){String query=request.getQueryString();if(query!=null&&!query.isEmpty())throw CompletionFailure.invalid("query","此操作不接受查詢參數");}
    private static void getOnly(HttpServletRequest request){if(!request.getMethod().equals("GET"))throw method();}
    private static CompletionFailure method(){return new CompletionFailure(405,"E0405","不支援此操作方法",null);}
    private static CompletionFailure mediaFailure(){return new CompletionFailure(415,"E0415","請使用支援的請求媒體格式",null);}
    private static SessionPrincipal principal(Authentication authentication){if(authentication==null||!(authentication.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;}
    private static <T> ResponseEntity<ApiEnvelope<T>> json(T data){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").body(ApiEnvelope.success(data));}
}
