package com.xksystem.logistics.fms;

import com.xksystem.logistics.fms.internal.DriverService;
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
public class DriverController {
    private final DriverService service;
    public DriverController(DriverService service){this.service=service;}
    @GetMapping("/drivers")
    public ApiEnvelope<DriverDtos.PageData<DriverDtos.DriverSummary>> drivers(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.drivers(principal(auth),DriverPageQuery.parse(query,"drivers")));
    }
    @GetMapping("/drivers/me")
    public ApiEnvelope<DriverDtos.DriverSelf> me(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.me(principal(auth)));
    }
    @GetMapping("/drivers/{id}")
    public ApiEnvelope<DriverDtos.Driver> driver(Authentication auth,@PathVariable String id,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.driver(principal(auth),DriverValidation.uuid(id,"id")));
    }
    @PostMapping(value="/drivers",consumes="application/json") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<DriverDtos.Driver> create(Authentication auth,@RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.create(principal(auth),DriverValidation.parse(body)));
    }
    @PatchMapping(value="/drivers/{id}",consumes="application/json")
    public ApiEnvelope<DriverDtos.Driver> update(Authentication auth,@PathVariable String id,@RequestBody byte[] body,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.update(principal(auth),DriverValidation.uuid(id,"id"),DriverValidation.parse(body)));
    }
    @GetMapping("/driver-options")
    public ApiEnvelope<DriverDtos.PageData<DriverDtos.DriverOption>> options(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.options(principal(auth),DriverPageQuery.parse(query,"options")));
    }
    @GetMapping("/driver-license-types")
    public ApiEnvelope<List<DriverDtos.DriverLicenseType>> licenseTypes(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        noQuery(query);return ApiEnvelope.success(service.licenseTypes(principal(auth)));
    }
    @GetMapping("/driver-account-options")
    public ApiEnvelope<DriverDtos.PageData<DriverDtos.DriverAccountOption>> accountOptions(Authentication auth,@RequestParam MultiValueMap<String,String> query) {
        return ApiEnvelope.success(service.accountOptions(principal(auth),DriverPageQuery.parse(query,"accounts")));
    }
    private static void noQuery(MultiValueMap<String,String> query){if(!query.isEmpty())throw UpmsException.field("query","此端點不接受查詢參數");}
    private static SessionPrincipal principal(Authentication auth) {
        if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;
    }
}
