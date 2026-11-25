package com.xksystem.logistics.fms;

import com.xksystem.logistics.fms.internal.VehicleService;
import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/fms")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class VehicleController {
    private final VehicleService service;
    public VehicleController(VehicleService service){this.service=service;}
    @GetMapping("/vehicles")
    public ApiEnvelope<VehicleDtos.PageData<VehicleDtos.Vehicle>> vehicles(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.vehicles(principal(auth),VehiclePageQuery.parse(query,false)));
    }
    @GetMapping("/vehicles/{id}")
    public ApiEnvelope<VehicleDtos.Vehicle> vehicle(Authentication auth,@PathVariable String id,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.vehicle(principal(auth),VehicleValidation.uuid(id,"id")));
    }
    @PostMapping(value="/vehicles",consumes="application/json") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<VehicleDtos.Vehicle> create(Authentication auth,@RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.create(principal(auth),VehicleValidation.parse(body)));
    }
    @PatchMapping(value="/vehicles/{id}",consumes="application/json")
    public ApiEnvelope<VehicleDtos.Vehicle> update(Authentication auth,@PathVariable String id,@RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.update(principal(auth),VehicleValidation.uuid(id,"id"),VehicleValidation.parse(body)));
    }
    @GetMapping("/vehicle-types")
    public ApiEnvelope<List<VehicleDtos.VehicleType>> types(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.types(principal(auth)));
    }
    @GetMapping("/vehicle-options")
    public ApiEnvelope<VehicleDtos.PageData<VehicleDtos.VehicleOption>> options(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.options(principal(auth),VehiclePageQuery.parse(query,true)));
    }
    private static void noQuery(MultiValueMap<String,String> query){if(!query.isEmpty())throw UpmsException.field("query","此端點不接受查詢參數");}
    private static SessionPrincipal principal(Authentication auth) {
        if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;
    }
}
