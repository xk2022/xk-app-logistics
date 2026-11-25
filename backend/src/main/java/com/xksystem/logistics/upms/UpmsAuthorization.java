package com.xksystem.logistics.upms;

import com.xksystem.logistics.upms.internal.UpmsService;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Cross-module authorization boundary. Never expose account repositories to other modules. */
@Service
public class UpmsAuthorization {
    private final UpmsService service;
    public UpmsAuthorization(UpmsService service) { this.service=service; }
    @Transactional(readOnly=true)
    public AccountDto requireAdmin(SessionPrincipal principal) { return service.authorizeAdmin(principal, false); }
    /** Caller transaction owns both the authorization locks and its subsequent domain writes. */
    @Transactional(propagation=Propagation.MANDATORY)
    public AccountDto requireAdminForWrite(SessionPrincipal principal) { return service.authorizeAdmin(principal, true); }
    @Transactional(readOnly=true)
    public AccountDto requireCustomerManager(SessionPrincipal principal) { return service.authorizeCustomerManager(principal, false); }
    @Transactional(propagation=Propagation.MANDATORY)
    public AccountDto requireCustomerManagerForWrite(SessionPrincipal principal) { return service.authorizeCustomerManager(principal, true); }
}
