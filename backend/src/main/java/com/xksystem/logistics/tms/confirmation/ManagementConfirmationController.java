package com.xksystem.logistics.tms.confirmation;
import com.xksystem.logistics.tms.internal.ManagementConfirmationService;
import static com.xksystem.logistics.tom.closure.ClosureHttp.*;
import com.xksystem.logistics.tom.closure.ClosureFailure;
import com.xksystem.logistics.tom.closure.ClosureValidation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tms/completions")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ManagementConfirmationController {
    private final ManagementConfirmationService service;
    public ManagementConfirmationController(ManagementConfirmationService service){this.service=service;}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(Authentication authentication,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){getOnly(request);return json(service.list(principal(authentication),ClosureValidation.query(query,"confirmations"),request.getSession(false)));}
    @GetMapping(value="/{completionId}/confirmation",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> detail(Authentication authentication,@PathVariable String completionId,HttpServletRequest request){getOnly(request);noQuery(request);return json(service.detail(principal(authentication),ClosureValidation.uuid(completionId,"completionId"),request.getSession(false)));}
    @GetMapping(value="/{completionId}/confirmation/history",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> history(Authentication authentication,@PathVariable String completionId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){getOnly(request);return json(service.history(principal(authentication),ClosureValidation.uuid(completionId,"completionId"),ClosureValidation.query(query,"page"),request.getSession(false)));}
    @PostMapping(value="/{completionId}/confirm",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirm(Authentication authentication,@PathVariable String completionId,HttpServletRequest request)throws IOException{
        noQuery(request);var actor=principal(authentication);var id=ClosureValidation.uuid(completionId,"completionId");
        String key=ClosureValidation.key(single(request,"Idempotency-Key",true));var originalSession=request.getSession(false);media(request);
        byte[] raw=ClosureValidation.bounded(request.getInputStream(),request.getContentLengthLong());
        var result=service.confirm(actor,id,raw,key,originalSession);return written(result.data(),result.replayed());
    }
    @RequestMapping({"","/{completionId}/confirmation","/{completionId}/confirmation/history","/{completionId}/confirm"})
    public void unsupported(HttpServletRequest request){boolean write=request.getServletPath().endsWith("/confirm");if(write?!request.getMethod().equals("POST"):!request.getMethod().equals("GET"))throw method();if(write)media(request);throw ClosureFailure.invalid("request","請依契約提供正確請求與回應格式");}
}
