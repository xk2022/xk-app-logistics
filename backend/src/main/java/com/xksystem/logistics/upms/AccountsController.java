package com.xksystem.logistics.upms;

import com.xksystem.logistics.upms.internal.UpmsService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/upms")
public class AccountsController {
    private final UpmsService service;
    public AccountsController(UpmsService service) { this.service=service; }
    public record CreateAccount(String username, String displayName, AccountRole role, String password) {}
    public record UpdateAccount(String displayName, AccountRole role, Boolean enabled, Long version) {}
    @GetMapping("/roles")
    public ApiEnvelope<List<AccountRole>> roles(Authentication authentication) { return ApiEnvelope.success(service.roles(AuthController.principal(authentication))); }
    @GetMapping("/accounts")
    public ApiEnvelope<List<AccountDto>> list(Authentication authentication) { return ApiEnvelope.success(service.list(AuthController.principal(authentication))); }
    @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED)
    public ApiEnvelope<AccountDto> create(@RequestBody CreateAccount input, Authentication authentication) {
        return ApiEnvelope.success(service.create(AuthController.principal(authentication), input.username(), input.displayName(), input.role(), input.password()));
    }
    @PatchMapping("/accounts/{id}")
    public ApiEnvelope<AccountDto> update(@PathVariable UUID id, @RequestBody UpdateAccount input, Authentication authentication) {
        return ApiEnvelope.success(service.update(AuthController.principal(authentication), id, input.displayName(), input.role(), input.enabled(), input.version()));
    }
}
