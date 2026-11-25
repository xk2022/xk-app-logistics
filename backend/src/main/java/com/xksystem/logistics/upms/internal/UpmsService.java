package com.xksystem.logistics.upms.internal;

import com.xksystem.logistics.upms.*;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import com.xksystem.logistics.upms.security.UpmsSessions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class UpmsService {
    private final AccountRepository accounts;
    private final UpmsLockRepository locks;
    private final AuditRepository audit;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwords;
    private final LoginThrottle throttle;
    private final UpmsSessions sessions;
    private final Clock clock;
    private final String dummyHash;
    private final Object[] loginStripes = new Object[128];
    @PersistenceContext private EntityManager entityManager;

    public UpmsService(AccountRepository accounts, UpmsLockRepository locks, AuditRepository audit,
                       PasswordEncoder encoder, PasswordPolicy passwords, LoginThrottle throttle,
                       UpmsSessions sessions, Clock clock) {
        this.accounts=accounts; this.locks=locks; this.audit=audit; this.encoder=encoder;
        this.passwords=passwords; this.throttle=throttle; this.sessions=sessions; this.clock=clock;
        this.dummyHash=encoder.encode(UUID.randomUUID().toString());
        java.util.Arrays.setAll(loginStripes, ignored -> new Object());
    }

    public record LoginResult(AccountDto account, long authVersion) {}

    @Transactional(noRollbackFor = UpmsException.class)
    public LoginResult login(String submittedUsername, String rawPassword) {
        String username = loginUsername(submittedUsername);
        synchronized (loginStripes[Math.floorMod(username.hashCode(), loginStripes.length)]) {
            String normalized;
            try { normalized = passwords.normalize(rawPassword); }
            catch (UpmsException e) { normalized = ""; }
            var account = accounts.findByUsername(username).orElse(null);
            // Offline recovery runs in another process. A persisted credential version change
            // therefore starts a new limiter namespace without relying on a local map reset.
            String limitKey=username + ":" + (account == null ? "unknown" : account.authVersion);
            boolean locked = throttle.locked(limitKey);
            // Always perform an expensive comparison, including unknown/disabled/throttled identities.
            boolean matches;
            try { matches = encoder.matches(normalized, account == null ? dummyHash : account.passwordHash); }
            catch (IllegalArgumentException malformedHash) {
                encoder.matches(normalized, dummyHash);
                matches=false;
            }
            if (locked || account == null || !matches || !usable(account)) {
                if (!locked) {
                    throttle.failure(limitKey);
                    audit.save(new UpmsAuditEntity("login:" + PasswordPolicy.sha256(username), null,
                            "LOGIN_FAILURE", "登入失敗；計數增加", clock.instant()));
                }
                throw UpmsException.unauthorized();
            }
            throttle.clear(limitKey);
            audit.save(new UpmsAuditEntity(account.username, account.id, "LOGIN_SUCCESS", "登入成功；失敗計數清除", clock.instant()));
            return new LoginResult(account.dto(), account.authVersion);
        }
    }

    @Transactional(readOnly=true)
    public AccountDto current(SessionPrincipal principal) { return active(principal, false).dto(); }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AccountDto authorizeAdmin(SessionPrincipal principal, boolean write) {
        if (write) lock();
        var actor=active(principal, write);
        requireAdmin(actor);
        return actor.dto();
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AccountDto authorizeCustomerManager(SessionPrincipal principal, boolean write) {
        if (write) lock();
        var actor=active(principal, write);
        if (actor.mustChangePassword || (actor.role!=AccountRole.ADMIN && actor.role!=AccountRole.DISPATCHER))
            throw UpmsException.forbidden();
        return actor.dto();
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AccountDto authorizeOrderManager(SessionPrincipal principal, boolean write) {
        return authorizeCustomerManager(principal, write);
    }

    @Transactional(readOnly=true)
    public List<AccountDto> list(SessionPrincipal principal) {
        requireAdmin(active(principal, false));
        return accounts.findAllByOrderByUsernameAsc().stream().map(AccountEntity::dto).toList();
    }

    @Transactional(readOnly=true)
    public List<AccountRole> roles(SessionPrincipal principal) {
        requireAdmin(active(principal, false));
        return List.of(AccountRole.values());
    }

    @Transactional
    public AccountDto create(SessionPrincipal principal, String username, String displayName,
                             AccountRole role, String password) {
        lock();
        var actor=active(principal, true); requireAdmin(actor);
        String canonical = username(username);
        if (accounts.findByUsername(canonical).isPresent()) throw new UpmsException(409, "帳號名稱已使用，請選擇其他名稱");
        var account = newAccount(canonical, displayName, role, password);
        accounts.saveAndFlush(account);
        record(actor.username, account.id, "ACCOUNT_CREATE", "建立帳號，首次登入須改密");
        return account.dto();
    }

    @Transactional
    public AccountDto update(SessionPrincipal principal, UUID id, String displayName, AccountRole role,
                             Boolean enabled, Long expectedVersion) {
        lock();
        var actor=active(principal, true); requireAdmin(actor);
        var account=accounts.findLockedById(id).orElseThrow(() -> new UpmsException(404, "找不到帳號"));
        if (expectedVersion == null || expectedVersion != account.version) throw new UpmsException(409, "帳號已由其他操作更新，請重新讀取後再試");
        if (role == null) throw UpmsException.field("role", "請選擇有效角色");
        if (enabled == null) throw UpmsException.field("enabled", "請提供啟用狀態");
        if (account.enabled && account.role == AccountRole.ADMIN && (!enabled || role != AccountRole.ADMIN)
                && accounts.countByRoleAndEnabled(AccountRole.ADMIN, true) <= 1)
            throw new UpmsException(409, "至少須保留一個啟用中的管理員帳號");
        boolean revoke = account.enabled != enabled || account.role != role;
        account.displayName=displayName(displayName); account.role=role; account.enabled=enabled;
        account.updatedAt=clock.instant();
        if (revoke) { account.authVersion++; afterCommitRevoke(account.id); }
        accounts.flush();
        record(actor.username, account.id, "ACCOUNT_UPDATE", revoke ? "帳號狀態或角色異動；撤銷認證" : "更新顯示名稱");
        return account.dto();
    }

    @Transactional
    public void changePassword(SessionPrincipal principal, String currentPassword, String newPassword) {
        lock();
        var account=active(principal, true);
        String current;
        try { current=passwords.normalize(currentPassword); } catch (UpmsException e) { current=""; }
        if (!encoder.matches(current, account.passwordHash)) throw UpmsException.field("currentPassword", "目前密碼不正確");
        String normalized=passwords.validate(newPassword, "newPassword");
        if (encoder.matches(normalized, account.passwordHash)) throw UpmsException.field("newPassword", "新密碼須與目前密碼不同");
        account.passwordHash=encoder.encode(normalized); account.mustChangePassword=false;
        account.temporaryPasswordExpiresAt=null; account.authVersion++; account.updatedAt=clock.instant();
        accounts.flush(); afterCommitRevoke(account.id);
        record(account.username, account.id, "PASSWORD_CHANGE", "本人改密；撤銷所有認證，須重新登入");
    }

    @Transactional
    public AccountDto bootstrap(String username, String displayName, String password, String operator, String reason) {
        validateOperator(operator, reason); lock();
        if (accounts.count() != 0) throw new UpmsException(409, "已有帳號，禁止再次初始化");
        var account=newAccount(username(username), displayName, AccountRole.ADMIN, password);
        accounts.saveAndFlush(account); record(operator, account.id, "ACCOUNT_BOOTSTRAP", reason);
        return account.dto();
    }

    @Transactional
    public void recover(String submittedUsername, String password, String operator, String reason) {
        validateOperator(operator, reason); lock();
        var found=accounts.findByUsername(username(submittedUsername)).orElseThrow(() -> new UpmsException(404, "找不到帳號"));
        var account=accounts.findLockedById(found.id).orElseThrow();
        if (!account.enabled) throw new UpmsException(409, "停用帳號須先由管理員啟用後才能復原");
        account.passwordHash=encoder.encode(passwords.validate(password, "password"));
        account.mustChangePassword=true; account.temporaryPasswordExpiresAt=clock.instant().plus(Duration.ofMinutes(15));
        account.authVersion++; account.updatedAt=clock.instant();
        accounts.flush(); afterCommitRevoke(account.id);
        record(operator, account.id, "ACCOUNT_RECOVER", reason + "；失敗計數清除，撤銷認證");
    }

    @Transactional
    public void logout(SessionPrincipal principal) {
        // Logout has no business writes; auth version is checked but other sessions remain usable.
        var account=active(principal, false);
        record(account.username, account.id, "LOGOUT", "本人登出");
    }

    private AccountEntity active(SessionPrincipal principal, boolean write) {
        if (principal == null) throw UpmsException.unauthorized();
        var account=accounts.findById(principal.id()).orElseThrow(UpmsException::unauthorized);
        if (write) entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        if (!usable(account) || account.authVersion != principal.authVersion()) throw UpmsException.unauthorized();
        return account;
    }
    private boolean usable(AccountEntity account) {
        return account.enabled && account.role != null && (!account.mustChangePassword
                || account.temporaryPasswordExpiresAt != null && clock.instant().isBefore(account.temporaryPasswordExpiresAt));
    }
    private void requireAdmin(AccountEntity account) {
        if (account.mustChangePassword || account.role != AccountRole.ADMIN) throw UpmsException.forbidden();
    }
    private AccountEntity newAccount(String username, String name, AccountRole role, String password) {
        if (role == null) throw UpmsException.field("role", "請選擇有效角色");
        var account=new AccountEntity(); account.id=UUID.randomUUID(); account.username=username;
        account.displayName=displayName(name); account.role=role; account.enabled=true;
        account.passwordHash=encoder.encode(passwords.validate(password, "password")); account.mustChangePassword=true;
        account.temporaryPasswordExpiresAt=clock.instant().plus(Duration.ofMinutes(15));
        account.createdAt=clock.instant(); account.updatedAt=clock.instant(); return account;
    }
    private void lock() { if (locks.lock() == null) throw new IllegalStateException("UPMS lock missing"); }
    private void record(String actor, UUID id, String action, String reason) {
        audit.save(new UpmsAuditEntity(actor, id, action, reason, clock.instant()));
    }
    private void afterCommitRevoke(UUID id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { sessions.revoke(id); }
        });
    }
    public static String username(String value) {
        if (value == null || !value.equals(value.strip()) || !value.toLowerCase(Locale.ROOT).matches("[a-z0-9][a-z0-9._-]{2,63}"))
            throw UpmsException.field("username", "帳號須為 3 至 64 個英數字、點、底線或連字號，開頭須為英數字");
        return value.toLowerCase(Locale.ROOT);
    }
    private static String loginUsername(String value) {
        if (value == null || value.length() > 256) return ""; // Cannot collide with a valid persisted username.
        return value.toLowerCase(Locale.ROOT);
    }
    private static String displayName(String value) {
        String normalized=Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC).strip();
        int length=normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 80 || normalized.codePoints().anyMatch(Character::isISOControl))
            throw UpmsException.field("displayName", "顯示名稱須為 1 至 80 個字元且不可含控制字元");
        return normalized;
    }
    private static void validateOperator(String operator, String reason) {
        if (operator == null || operator.isBlank() || operator.length() > 128 || operator.codePoints().anyMatch(Character::isISOControl))
            throw UpmsException.field("operator", "請提供有效操作者識別，最多 128 字元");
        if (reason == null || reason.isBlank() || reason.length() > 450 || reason.codePoints().anyMatch(Character::isISOControl))
            throw UpmsException.field("reason", "請提供操作原因，最多 450 字元，勿填入秘密");
    }
}
