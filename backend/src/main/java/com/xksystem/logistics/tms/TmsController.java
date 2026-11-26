package com.xksystem.logistics.tms;

import com.xksystem.logistics.tms.internal.TmsService;
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
@RequestMapping("/api/v1/tms")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class TmsController {
    private final TmsService service;
    public TmsController(TmsService service){this.service=service;}

    @GetMapping(value="/tasks",produces=MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<TmsDtos.Page<TmsDtos.TaskSummary>> tasks(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.tasks(principal(auth),TmsQuery.parse(query,"tasks")));
    }
    @GetMapping(value="/tasks/{taskId}/dispatch",produces=MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<TmsDtos.DispatchDetail> detail(Authentication auth,@PathVariable String taskId,
            @RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.detail(principal(auth),TmsValidation.uuid(taskId,"taskId"),TmsQuery.parse(query,"history")));
    }
    @GetMapping(value="/resources",produces=MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<TmsDtos.Page<TmsDtos.ResourceCandidate>> resources(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.resources(principal(auth),TmsQuery.parse(query,"resources")));
    }
    @GetMapping(value="/my-tasks",produces=MediaType.APPLICATION_JSON_VALUE)
    public ApiEnvelope<TmsDtos.Page<TmsDtos.MyTask>> mine(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.mine(principal(auth),TmsQuery.parse(query,"self")));
    }

    @PostMapping(value="/tasks/{taskId}/dispatch",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<TmsDtos.CommandResult>> assign(Authentication auth,@PathVariable String taskId,
            @RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        return written(auth,taskId,body,query,request,TmsValidation.Action.ASSIGN);
    }
    @PostMapping(value="/dispatches/{id}/revoke",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<TmsDtos.CommandResult>> revoke(Authentication auth,@PathVariable String id,
            @RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        return written(auth,id,body,query,request,TmsValidation.Action.REVOKE);
    }
    @PostMapping(value="/dispatches/{id}/reassign",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<TmsDtos.CommandResult>> reassign(Authentication auth,@PathVariable String id,
            @RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        return written(auth,id,body,query,request,TmsValidation.Action.REASSIGN);
    }

    private ResponseEntity<ApiEnvelope<TmsDtos.CommandResult>> written(Authentication auth,String target,byte[] body,
            MultiValueMap<String,String> query,HttpServletRequest request,TmsValidation.Action action) {
        if(!query.isEmpty())throw TmsFailure.invalid("query","此寫入操作不接受查詢參數");
        var headers=Collections.list(request.getHeaders("Idempotency-Key"));
        if(headers.size()!=1)throw TmsFailure.invalid("Idempotency-Key","請提供單一有效重送識別");
        String key=TmsValidation.key(headers.getFirst());
        var result=service.write(principal(auth),TmsValidation.uuid(target,action==TmsValidation.Action.ASSIGN?"taskId":"id"),body,key,action);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(ApiEnvelope.success(result.data()));
    }

    // Keep known-path protocol failures inside the module's exact company contract.
    @RequestMapping({"/tasks","/tasks/{taskId}/dispatch","/resources","/my-tasks","/dispatches/{id}/revoke","/dispatches/{id}/reassign"})
    public void unsupported(HttpServletRequest request) {
        String path=request.getServletPath(),method=request.getMethod();
        boolean taskDetail=path.matches("/api/v1/tms/tasks/[^/]+/dispatch");
        boolean command=path.matches("/api/v1/tms/dispatches/[^/]+/(revoke|reassign)");
        boolean allowed=taskDetail?(method.equals("GET")||method.equals("POST")):command?method.equals("POST"):method.equals("GET");
        if(!allowed)throw new TmsFailure(405,"E0405","不支援此操作方法",null);
        if(method.equals("POST")) {
            MediaType contentType;
            try{contentType=MediaType.parseMediaType(request.getContentType()==null?"application/octet-stream":request.getContentType());}
            catch(IllegalArgumentException invalid){throw new TmsFailure(415,"E0415","請使用 JSON 格式提交資料",null);}
            if(!MediaType.APPLICATION_JSON.isCompatibleWith(contentType))throw new TmsFailure(415,"E0415","請使用 JSON 格式提交資料",null);
        }
        throw TmsFailure.invalid("request","請依契約使用 JSON 回應與請求格式");
    }
    @RequestMapping({"","/{*unmatched}"})
    public void unknown(){throw TmsFailure.missing();}

    private static SessionPrincipal principal(Authentication auth) {
        if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();
        return principal;
    }
}
