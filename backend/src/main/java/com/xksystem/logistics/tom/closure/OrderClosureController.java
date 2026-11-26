package com.xksystem.logistics.tom.closure;
import com.xksystem.logistics.tom.internal.OrderClosureService;
import java.util.UUID;
import static com.xksystem.logistics.tom.closure.ClosureHttp.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class OrderClosureController {
    private final OrderClosureService service;
    public OrderClosureController(OrderClosureService service){this.service=service;}
    @GetMapping(value="/closure-worklist",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> worklist(Authentication authentication,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){getOnly(request);return json(service.worklist(principal(authentication),ClosureValidation.query(query,"worklist"),request.getSession(false)));}
    @GetMapping(value="/{orderId}/closure",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> detail(Authentication authentication,@PathVariable String orderId,HttpServletRequest request){getOnly(request);noQuery(request);return json(service.detail(principal(authentication),ClosureValidation.uuid(orderId,"orderId"),request.getSession(false)));}
    @GetMapping(value="/{orderId}/closure/tasks",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tasks(Authentication authentication,@PathVariable String orderId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){getOnly(request);return json(service.tasks(principal(authentication),ClosureValidation.uuid(orderId,"orderId"),ClosureValidation.query(query,"page"),request.getSession(false)));}
    @GetMapping(value="/{orderId}/closure/history",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> history(Authentication authentication,@PathVariable String orderId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){getOnly(request);return json(service.history(principal(authentication),ClosureValidation.uuid(orderId,"orderId"),ClosureValidation.query(query,"page"),request.getSession(false)));}
    @PostMapping(value="/{orderId}/close",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> close(Authentication authentication,@PathVariable String orderId,HttpServletRequest request)throws IOException{
        noQuery(request);var actor=principal(authentication);var id=ClosureValidation.uuid(orderId,"orderId");
        String key=ClosureValidation.key(single(request,"Idempotency-Key",true));var originalSession=request.getSession(false);media(request);
        byte[] raw=ClosureValidation.bounded(request.getInputStream(),request.getContentLengthLong());
        var result=service.close(actor,id,raw,key,UUID.randomUUID().toString(),originalSession);return written(result.data(),result.replayed());
    }
    @RequestMapping({"/closure-worklist","/{orderId}/closure","/{orderId}/closure/tasks","/{orderId}/closure/history","/{orderId}/close"})
    public void unsupported(HttpServletRequest request){boolean write=request.getServletPath().endsWith("/close");if(write?!request.getMethod().equals("POST"):!request.getMethod().equals("GET"))throw method();if(write)media(request);throw ClosureFailure.invalid("request","請依契約提供正確請求與回應格式");}
}
