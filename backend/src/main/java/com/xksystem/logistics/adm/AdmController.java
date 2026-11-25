package com.xksystem.logistics.adm;

import com.xksystem.logistics.adm.internal.AdmService;
import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/adm")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AdmController {
    private final AdmService service;
    public AdmController(AdmService service){this.service=service;}
    @GetMapping("/categories")
    public ApiEnvelope<AdmDtos.PageData<AdmDtos.Category>> categories(Authentication auth,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.categories(principal(auth),AdmPageQuery.parse(query,"categories")));
    }
    @PostMapping("/categories") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<AdmDtos.Category> createCategory(Authentication auth,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);
        AdmValidation.fields(body,"code","name","enabled","sortOrder");
        return ApiEnvelope.success(service.createCategory(principal(auth),s(body,"code"),s(body,"name"),b(body,"enabled"),AdmValidation.sortOrder(body)));
    }
    @PatchMapping("/categories/{id}")
    public ApiEnvelope<AdmDtos.Category> updateCategory(Authentication auth,@PathVariable UUID id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);
        AdmValidation.fields(body,"name","enabled","sortOrder","version");
        return ApiEnvelope.success(service.updateCategory(principal(auth),id,s(body,"name"),b(body,"enabled"),AdmValidation.sortOrder(body),AdmValidation.version(body)));
    }
    @GetMapping("/categories/{id}/items")
    public ApiEnvelope<AdmDtos.PageData<AdmDtos.Item>> items(Authentication auth,@PathVariable UUID id,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.items(principal(auth),id,AdmPageQuery.parse(query,"items")));
    }
    @PostMapping("/categories/{id}/items") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<AdmDtos.Item> createItem(Authentication auth,@PathVariable UUID id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);
        AdmValidation.fields(body,"code","label","enabled","sortOrder");
        return ApiEnvelope.success(service.createItem(principal(auth),id,s(body,"code"),s(body,"label"),b(body,"enabled"),AdmValidation.sortOrder(body)));
    }
    @PatchMapping("/items/{id}")
    public ApiEnvelope<AdmDtos.Item> updateItem(Authentication auth,@PathVariable UUID id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);
        AdmValidation.fields(body,"label","enabled","sortOrder","version");
        return ApiEnvelope.success(service.updateItem(principal(auth),id,s(body,"label"),b(body,"enabled"),AdmValidation.sortOrder(body),AdmValidation.version(body)));
    }
    @GetMapping("/parameters")
    public ApiEnvelope<AdmDtos.PageData<AdmDtos.Parameter>> parameters(Authentication auth,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.parameters(principal(auth),AdmPageQuery.parse(query,"parameters")));
    }
    @GetMapping("/parameter-definitions")
    public ApiEnvelope<List<AdmDtos.Definition>> definitions(Authentication auth,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);return ApiEnvelope.success(service.definitions(principal(auth)));
    }
    @PostMapping("/parameters") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<AdmDtos.Parameter> createParameter(Authentication auth,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);
        AdmValidation.fields(body,"key","name","valueType","valueText","description","enabled");
        ParameterType type;
        try{type=ParameterType.valueOf(s(body,"valueType"));}catch(IllegalArgumentException e){throw UpmsException.field("valueType","請提供 TEXT、NUMBER 或 BOOLEAN");}
        return ApiEnvelope.success(service.createParameter(principal(auth),s(body,"key"),s(body,"name"),type,s(body,"valueText"),s(body,"description"),b(body,"enabled")));
    }
    @PatchMapping("/parameters/{id}")
    public ApiEnvelope<AdmDtos.Parameter> updateParameter(Authentication auth,@PathVariable UUID id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);
        AdmValidation.fields(body,"name","valueText","description","enabled","version");
        return ApiEnvelope.success(service.updateParameter(principal(auth),id,s(body,"name"),s(body,"valueText"),s(body,"description"),b(body,"enabled"),AdmValidation.version(body)));
    }
    @GetMapping("/options/{categoryCode}")
    public ApiEnvelope<List<AdmDtos.Item>> options(Authentication auth,@PathVariable String categoryCode,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);return ApiEnvelope.success(service.options(principal(auth),categoryCode));
    }
    @GetMapping("/change-logs")
    public ApiEnvelope<AdmDtos.PageData<AdmDtos.ChangeLog>> logs(Authentication auth,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.logs(principal(auth),AdmPageQuery.parse(query,"logs")));
    }
    private static void noQuery(MultiValueMap<String,String> query){if(!query.isEmpty())throw UpmsException.field("query","此端點不接受查詢參數");}
    private static String s(JsonNode body,String field){return AdmValidation.string(body,field);}
    private static boolean b(JsonNode body,String field){return AdmValidation.bool(body,field);}
    private static SessionPrincipal principal(Authentication auth){
        if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;
    }
}
