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
    @Transactional(readOnly=true)
    public AccountDto requireOrderManager(SessionPrincipal principal) { return service.authorizeOrderManager(principal, false); }
    @Transactional(propagation=Propagation.MANDATORY)
    public AccountDto requireOrderManagerForWrite(SessionPrincipal principal) { return service.authorizeOrderManager(principal, true); }
    @Transactional(readOnly=true)
    public AccountDto requireVehicleManager(SessionPrincipal principal) { return service.authorizeVehicleManager(principal, false); }
    @Transactional(propagation=Propagation.MANDATORY)
    public AccountDto requireVehicleManagerForWrite(SessionPrincipal principal) { return service.authorizeVehicleManager(principal, true); }
    @Transactional(readOnly=true)
    public AccountDto requireDriverManager(SessionPrincipal principal) { return service.authorizeDriverManager(principal, false); }
    @Transactional(propagation=Propagation.MANDATORY)
    public AccountDto requireDriverManagerForWrite(SessionPrincipal principal) { return service.authorizeDriverManager(principal, true); }
    /** A lock-capable read: coordinates the caller's own binding lookup with account lifecycle changes. */
    @Transactional(propagation=Propagation.MANDATORY)
    public AccountDto requireDriverSelfWithLocks(SessionPrincipal principal) { return service.authorizeDriverSelfWithLocks(principal); }
    @Transactional(readOnly=true)
    public AccountDto requireDispatchManager(SessionPrincipal principal) { return service.authorizeOrderManager(principal,false); }
    @Transactional(propagation=Propagation.MANDATORY)
    public AccountDto requireDispatchManagerForWrite(SessionPrincipal principal) { return service.authorizeOrderManager(principal,true); }
}
