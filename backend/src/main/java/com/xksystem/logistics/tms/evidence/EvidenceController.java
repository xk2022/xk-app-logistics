package com.xksystem.logistics.tms.evidence;

import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.internal.EvidenceService;
import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tms/my-tasks/{assignmentId}")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class EvidenceController {
    private final EvidenceService service;
    public EvidenceController(EvidenceService service){this.service=service;}
    @GetMapping(value="/progress-policy",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<EvidenceDtos.PolicyView>> policy(Authentication auth,@PathVariable String assignmentId,
            @RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);noQuery(query);return json(service.policy(principal(auth),id(assignmentId),request.getSession(false)));
    }
    @GetMapping(value="/evidence",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<TmsDtos.Page<EvidenceDtos.Evidence>>> list(Authentication auth,@PathVariable String assignmentId,
            @RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);return json(service.list(principal(auth),id(assignmentId),EvidenceValidation.page(query),request.getSession(false)));
    }
    @GetMapping(value="/evidence/{evidenceId}/content",produces="image/png")
    public ResponseEntity<byte[]> content(Authentication auth,@PathVariable String assignmentId,@PathVariable String evidenceId,
            @RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        getOnly(request);noQuery(query);if(request.getHeader("Range")!=null)throw EvidenceFailure.invalid("Range","此私有內容不接受範圍請求");
        byte[] bytes=service.content(principal(auth),id(assignmentId),EvidenceValidation.uuid(evidenceId,"evidenceId"),request.getSession(false));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(bytes.length)
                .header("Cache-Control","no-store, private").header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","inline; filename=\"evidence.png\"").body(bytes);
    }
    @PostMapping(value="/progress",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<EvidenceDtos.ProgressResult>> progress(Authentication auth,@PathVariable String assignmentId,
            @RequestBody byte[] raw,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        noQuery(query);var written=service.progress(principal(auth),id(assignmentId),raw,key(request),request.getSession(false));
        return written(written);
    }
    @PostMapping(value="/evidence",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<EvidenceDtos.Evidence>> upload(Authentication auth,@PathVariable String assignmentId,
            HttpServletRequest request)throws IOException {
        if(request.getQueryString()!=null)throw EvidenceFailure.invalid("query","此操作不接受查詢參數");
        String key=key(request);long version=EvidenceValidation.headerVersion(single(request,"X-Execution-Version",true));
        String rawType=single(request,"Content-Type",false);if(rawType==null)throw media();MediaType media;
        try{media=MediaType.parseMediaType(rawType);}catch(IllegalArgumentException invalid){throw media();}
        if(!media.getType().equalsIgnoreCase("image")||!media.getSubtype().equalsIgnoreCase("png")||!media.getParameters().isEmpty())throw media();
        String encoding=single(request,"Content-Encoding",false);if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw media();
        var session=request.getSession(false);var actor=principal(auth);var assignment=id(assignmentId);
        byte[] bytes=EvidenceValidation.bounded(request.getInputStream(),request.getContentLengthLong());
        return written(service.upload(actor,assignment,version,"image/png",bytes,key,session));
    }
    @RequestMapping({"/progress-policy","/progress","/evidence","/evidence/{evidenceId}/content"})
    public void unsupported(HttpServletRequest request) {
        String path=request.getServletPath(),method=request.getMethod();
        boolean evidence=path.endsWith("/evidence"),progress=path.endsWith("/progress");
        boolean allowed=evidence?(method.equals("GET")||method.equals("POST")):progress?method.equals("POST"):method.equals("GET");
        if(!allowed)throw new EvidenceFailure(405,"E0405","不支援此操作方法",null);
        if(progress&&method.equals("POST")) {
            try{if(!MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()==null?"application/octet-stream":request.getContentType())))throw media();}
            catch(IllegalArgumentException invalid){throw media();}
        }
        throw EvidenceFailure.invalid("request","請依契約提供正確回應與請求格式");
    }
    private static <T> ResponseEntity<ApiEnvelope<T>> json(T data){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").body(ApiEnvelope.success(data));}
    private static <T> ResponseEntity<ApiEnvelope<T>> written(EvidenceService.Written<T> result){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(ApiEnvelope.success(result.data()));}
    private static java.util.UUID id(String value){return EvidenceValidation.uuid(value,"assignmentId");}
    private static String key(HttpServletRequest request){return EvidenceValidation.key(single(request,"Idempotency-Key",true));}
    private static String single(HttpServletRequest request,String name,boolean required) {
        var values=Collections.list(request.getHeaders(name));if(values.size()>1||required&&values.size()!=1)throw EvidenceFailure.invalid(name,"請提供單一有效欄位");
        return values.isEmpty()?null:values.getFirst();
    }
    private static void noQuery(MultiValueMap<String,String> query){if(!query.isEmpty())throw EvidenceFailure.invalid("query","此操作不接受查詢參數");}
    private static void getOnly(HttpServletRequest request){if(!request.getMethod().equals("GET"))throw new EvidenceFailure(405,"E0405","不支援此操作方法",null);}
    private static EvidenceFailure media(){return new EvidenceFailure(415,"E0415","請使用支援的請求媒體格式",null);}
    private static SessionPrincipal principal(Authentication auth){if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;}
}
