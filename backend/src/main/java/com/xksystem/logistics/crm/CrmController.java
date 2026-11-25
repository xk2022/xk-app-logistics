package com.xksystem.logistics.crm;

import com.xksystem.logistics.crm.internal.CrmService;
import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/crm")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class CrmController {
    private final CrmService service;
    private static final Set<String> CUSTOMER_CREATE=Set.of("customerCode","name","taxId","phone","email","enabled");
    private static final Set<String> CUSTOMER_PATCH=Set.of("name","taxId","phone","email","enabled","version");
    private static final Set<String> CONTACT_CREATE=Set.of("name","title","phone","email","enabled");
    private static final Set<String> CONTACT_PATCH=Set.of("name","title","phone","email","enabled","version");
    private static final Set<String> ADDRESS_CREATE=Set.of("label","address","addressType","enabled");
    private static final Set<String> ADDRESS_PATCH=Set.of("label","address","addressType","enabled","version");
    public CrmController(CrmService service){this.service=service;}
    @GetMapping("/customers")
    public ApiEnvelope<CrmDtos.PageData<CrmDtos.Customer>> customers(Authentication auth,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.customers(principal(auth),CrmPageQuery.parse(query,"customers")));
    }
    @GetMapping("/customers/{id}")
    public ApiEnvelope<CrmDtos.Customer> customer(Authentication auth,@PathVariable UUID id,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);return ApiEnvelope.success(service.customer(principal(auth),id));
    }
    @PostMapping("/customers") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<CrmDtos.Customer> createCustomer(Authentication auth,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);CrmValidation.fields(body,CUSTOMER_CREATE,Set.of("customerCode","name"));
        return ApiEnvelope.success(service.createCustomer(principal(auth),body));
    }
    @PatchMapping("/customers/{id}")
    public ApiEnvelope<CrmDtos.Customer> updateCustomer(Authentication auth,@PathVariable UUID id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);CrmValidation.fields(body,CUSTOMER_PATCH,Set.of("version"));
        return ApiEnvelope.success(service.updateCustomer(principal(auth),id,body));
    }
    @GetMapping("/customers/{id}/contacts")
    public ApiEnvelope<CrmDtos.PageData<CrmDtos.Contact>> contacts(Authentication auth,@PathVariable UUID id,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.contacts(principal(auth),id,CrmPageQuery.parse(query,"contacts")));
    }
    @PostMapping("/customers/{id}/contacts") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<CrmDtos.Contact> createContact(Authentication auth,@PathVariable UUID id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);CrmValidation.fields(body,CONTACT_CREATE,Set.of("name"));
        return ApiEnvelope.success(service.createContact(principal(auth),id,body));
    }
    @PatchMapping("/customers/{id}/contacts/{contactId}")
    public ApiEnvelope<CrmDtos.Contact> updateContact(Authentication auth,@PathVariable UUID id,@PathVariable UUID contactId,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);CrmValidation.fields(body,CONTACT_PATCH,Set.of("version"));
        return ApiEnvelope.success(service.updateContact(principal(auth),id,contactId,body));
    }
    @GetMapping("/customers/{id}/addresses")
    public ApiEnvelope<CrmDtos.PageData<CrmDtos.Address>> addresses(Authentication auth,@PathVariable UUID id,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.addresses(principal(auth),id,CrmPageQuery.parse(query,"addresses")));
    }
    @PostMapping("/customers/{id}/addresses") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<CrmDtos.Address> createAddress(Authentication auth,@PathVariable UUID id,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);CrmValidation.fields(body,ADDRESS_CREATE,Set.of("address"));
        return ApiEnvelope.success(service.createAddress(principal(auth),id,body));
    }
    @PatchMapping("/customers/{id}/addresses/{addressId}")
    public ApiEnvelope<CrmDtos.Address> updateAddress(Authentication auth,@PathVariable UUID id,@PathVariable UUID addressId,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> query){
        noQuery(query);CrmValidation.fields(body,ADDRESS_PATCH,Set.of("version"));
        return ApiEnvelope.success(service.updateAddress(principal(auth),id,addressId,body));
    }
    @GetMapping("/customer-options")
    public ApiEnvelope<CrmDtos.PageData<CrmDtos.CustomerOption>> options(Authentication auth,@RequestParam MultiValueMap<String,String> query){
        return ApiEnvelope.success(service.options(principal(auth),CrmPageQuery.parse(query,"options")));
    }
    private static void noQuery(MultiValueMap<String,String> query){if(!query.isEmpty())throw UpmsException.field("query","此端點不接受查詢參數");}
    private static SessionPrincipal principal(Authentication auth){
        if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;
    }
}
