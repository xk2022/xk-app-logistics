package com.xksystem.logistics.tms.exceptions;

import com.xksystem.logistics.tms.internal.ExceptionCaseService;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tms")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ExceptionController {
    private static final String SELF="/my-tasks/{assignmentId}/exceptions";
    private static final String CASE=SELF+"/{exceptionId}";
    private static final String REQUEST=CASE+"/supplement-requests/{requestId}";
    private static final String MANAGER="/exceptions";
    private final ExceptionCaseService service;
    public ExceptionController(ExceptionCaseService service){this.service=service;}

    @GetMapping(value=SELF,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> driverList(Authentication auth,@PathVariable String assignmentId,
            @RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);return json(service.driverList(principal(auth),id(assignmentId,"assignmentId"),ExceptionValidation.query(query,"self"),request.getSession(false)));
    }
    @PostMapping(value=SELF,consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> report(Authentication auth,@PathVariable String assignmentId,HttpServletRequest request)throws IOException {
        noQuery(request);var actor=principal(auth);var assignment=id(assignmentId,"assignmentId");String key=key(request);var session=request.getSession(false);
        return written(service.report(actor,assignment,jsonBody(request),key,session));
    }
    @GetMapping(value=CASE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> driverDetail(Authentication auth,@PathVariable String assignmentId,@PathVariable String exceptionId,HttpServletRequest request) {
        getOnly(request);noQuery(request);return json(service.driverDetail(principal(auth),id(assignmentId,"assignmentId"),id(exceptionId,"exceptionId"),request.getSession(false)));
    }
    @GetMapping(value=CASE+"/history",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> driverHistory(Authentication auth,@PathVariable String assignmentId,@PathVariable String exceptionId,
            @RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);return json(service.driverHistory(principal(auth),id(assignmentId,"assignmentId"),id(exceptionId,"exceptionId"),ExceptionValidation.query(query,"page"),request.getSession(false)));
    }
    @PostMapping(value=REQUEST+"/evidence",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upload(Authentication auth,@PathVariable String assignmentId,@PathVariable String exceptionId,
            @PathVariable String requestId,HttpServletRequest request)throws IOException {
        noQuery(request);var actor=principal(auth);var assignment=id(assignmentId,"assignmentId");var exception=id(exceptionId,"exceptionId");var requested=id(requestId,"requestId");
        String key=key(request);long exceptionVersion=ExceptionValidation.headerVersion(single(request,"X-Exception-Version",true),"X-Exception-Version");
        long executionVersion=ExceptionValidation.headerVersion(single(request,"X-Execution-Version",true),"X-Execution-Version");
        media(request,MediaType.IMAGE_PNG,false);var session=request.getSession(false);
        byte[] bytes=ExceptionValidation.bounded(request.getInputStream(),request.getContentLengthLong(),1_048_576);
        return written(service.upload(actor,assignment,exception,requested,exceptionVersion,executionVersion,"image/png",bytes,key,session));
    }
    @PostMapping(value=REQUEST+"/submit",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(Authentication auth,@PathVariable String assignmentId,@PathVariable String exceptionId,
            @PathVariable String requestId,HttpServletRequest request)throws IOException {
        noQuery(request);var actor=principal(auth);var assignment=id(assignmentId,"assignmentId");var exception=id(exceptionId,"exceptionId");var requested=id(requestId,"requestId");
        String key=key(request);var session=request.getSession(false);return written(service.submit(actor,assignment,exception,requested,jsonBody(request),key,session));
    }
    @GetMapping(value=MANAGER,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> managerList(Authentication auth,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);return json(service.managerList(principal(auth),ExceptionValidation.query(query,"manager"),request.getSession(false)));
    }
    @GetMapping(value=MANAGER+"/{exceptionId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> managerDetail(Authentication auth,@PathVariable String exceptionId,HttpServletRequest request) {
        getOnly(request);noQuery(request);return json(service.managerDetail(principal(auth),id(exceptionId,"exceptionId"),request.getSession(false)));
    }
    @GetMapping(value=MANAGER+"/{exceptionId}/history",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> managerHistory(Authentication auth,@PathVariable String exceptionId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);return json(service.managerHistory(principal(auth),id(exceptionId,"exceptionId"),ExceptionValidation.query(query,"page"),request.getSession(false)));
    }
    @PostMapping(value=MANAGER+"/{exceptionId}/supplement-requests",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> requestSupplement(Authentication auth,@PathVariable String exceptionId,HttpServletRequest request)throws IOException {
        noQuery(request);var actor=principal(auth);var exception=id(exceptionId,"exceptionId");String key=key(request);var session=request.getSession(false);
        return written(service.requestSupplement(actor,exception,jsonBody(request),key,session));
    }
    @PostMapping(value=MANAGER+"/{exceptionId}/resolve",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolve(Authentication auth,@PathVariable String exceptionId,HttpServletRequest request)throws IOException {
        noQuery(request);var actor=principal(auth);var exception=id(exceptionId,"exceptionId");String key=key(request);var session=request.getSession(false);
        return written(service.resolve(actor,exception,jsonBody(request),key,session));
    }
    @GetMapping(value=MANAGER+"/{exceptionId}/evidence",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> managerEvidence(Authentication auth,@PathVariable String exceptionId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);return json(service.managerEvidence(principal(auth),id(exceptionId,"exceptionId"),ExceptionValidation.query(query,"page"),request.getSession(false)));
    }
    @GetMapping(value=MANAGER+"/{exceptionId}/evidence/{evidenceId}/content",produces="image/png")
    public ResponseEntity<byte[]> managerContent(Authentication auth,@PathVariable String exceptionId,@PathVariable String evidenceId,HttpServletRequest request) {
        getOnly(request);noQuery(request);if(request.getHeader("Range")!=null)throw ExceptionFailure.invalid("Range","此私有內容不接受範圍請求");
        byte[] bytes=service.managerContent(principal(auth),id(exceptionId,"exceptionId"),id(evidenceId,"evidenceId"),request.getSession(false));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(bytes.length).header("Cache-Control","no-store, private")
                .header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=\"evidence.png\"").body(bytes);
    }
    @RequestMapping({SELF,CASE,CASE+"/history",REQUEST+"/evidence",REQUEST+"/submit",MANAGER,MANAGER+"/{exceptionId}",
            MANAGER+"/{exceptionId}/history",MANAGER+"/{exceptionId}/supplement-requests",MANAGER+"/{exceptionId}/resolve",
            MANAGER+"/{exceptionId}/evidence",MANAGER+"/{exceptionId}/evidence/{evidenceId}/content"})
    public void unsupported(HttpServletRequest request) {
        String path=request.getServletPath(),method=request.getMethod();boolean raw=path.matches("/api/v1/tms/my-tasks/[^/]+/exceptions/[^/]+/supplement-requests/[^/]+/evidence");
        boolean post=raw||path.endsWith("/submit")||path.endsWith("/supplement-requests")||path.endsWith("/resolve");
        boolean selfCollection=path.matches("/api/v1/tms/my-tasks/[^/]+/exceptions");
        if(post?!method.equals("POST"):selfCollection?!method.equals("GET")&&!method.equals("POST"):!method.equals("GET"))throw method();
        if(method.equals("POST"))media(request,raw?MediaType.IMAGE_PNG:MediaType.APPLICATION_JSON,!raw);
        throw ExceptionFailure.invalid("request","請依契約提供正確請求與回應格式");
    }
    private static byte[] jsonBody(HttpServletRequest request)throws IOException {
        media(request,MediaType.APPLICATION_JSON,true);return ExceptionValidation.bounded(request.getInputStream(),request.getContentLengthLong(),16_384);
    }
    private static void media(HttpServletRequest request,MediaType expected,boolean json) {
        String value=single(request,"Content-Type",!json);if(value==null)throw mediaFailure();MediaType actual;
        try{actual=MediaType.parseMediaType(value);}catch(IllegalArgumentException invalid){throw mediaFailure();}
        if(!actual.getType().equalsIgnoreCase(expected.getType())||!actual.getSubtype().equalsIgnoreCase(expected.getSubtype()))throw mediaFailure();
        if(json){
            for(String parameter:actual.getParameters().keySet())if(!parameter.equalsIgnoreCase("charset"))throw mediaFailure();
            if(actual.getCharset()!=null&&!actual.getCharset().equals(StandardCharsets.UTF_8))throw mediaFailure();
        }
        else if(!actual.getParameters().isEmpty())throw mediaFailure();
        String encoding=single(request,"Content-Encoding",false);if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw mediaFailure();
    }
    private static String single(HttpServletRequest request,String name,boolean required) {
        var values=Collections.list(request.getHeaders(name));if(values.size()>1||required&&values.size()!=1)throw ExceptionFailure.invalid(name,"請提供單一有效欄位");
        return values.isEmpty()?null:values.getFirst();
    }
    private static String key(HttpServletRequest request){return ExceptionValidation.key(single(request,"Idempotency-Key",true));}
    private static UUID id(String value,String field){return ExceptionValidation.uuid(value,field);}
    private static void noQuery(HttpServletRequest request){String query=request.getQueryString();if(query!=null&&!query.isEmpty())throw ExceptionFailure.invalid("query","此操作不接受查詢參數");}
    private static void getOnly(HttpServletRequest request){if(!request.getMethod().equals("GET"))throw method();}
    private static ExceptionFailure method(){return new ExceptionFailure(405,"E0405","不支援此操作方法",null);}
    private static ExceptionFailure mediaFailure(){return new ExceptionFailure(415,"E0415","請使用支援的請求媒體格式",null);}
    private static SessionPrincipal principal(Authentication auth){if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;}
    private static <T> ResponseEntity<ApiEnvelope<T>> json(T data){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").body(ApiEnvelope.success(data));}
    private static <T> ResponseEntity<ApiEnvelope<T>> written(ExceptionCaseService.Written<T> result){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(ApiEnvelope.success(result.data()));}
}
