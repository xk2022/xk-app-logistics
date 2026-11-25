package com.xksystem.logistics.upms.internal;

import com.xksystem.logistics.upms.AccountRole;
import com.xksystem.logistics.upms.DriverAccountQueries;
import com.xksystem.logistics.upms.UpmsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service @Transactional(readOnly=true)
public class DriverAccountService implements DriverAccountQueries {
    private final AccountRepository accounts;
    private final Clock clock;
    @PersistenceContext private EntityManager entityManager;
    public DriverAccountService(AccountRepository accounts,Clock clock){this.accounts=accounts;this.clock=clock;}
    @Override public Optional<Summary> findCurrent(UUID id) {
        if(id==null)return Optional.empty();
        // Scalar projection bypasses a stale account in an outer persistence context.
        return accounts.driverSummary(id).map(account->new Summary(account.getId(),account.getUsername(),account.getDisplayName(),
                eligible(account.getRole(),account.getEnabled(),account.getMustChangePassword(),account.getTemporaryPasswordExpiresAt(),clock.instant())));
    }
    @Override public Page<Candidate> candidates(int page,int size,String q) {
        if(page<0||page>100_000||size<1||size>100)throw UpmsException.field("page","查詢數值超出允許範圍");
        String search=search(q);Instant now=clock.instant();
        Specification<AccountEntity> eligible=(root,query,cb)->{
            var predicate=cb.and(cb.equal(root.get("role"),AccountRole.DRIVER),cb.isTrue(root.get("enabled")),
                    cb.or(cb.isFalse(root.get("mustChangePassword")),cb.greaterThan(root.get("temporaryPasswordExpiresAt"),now)));
            if(search.isEmpty())return predicate;
            String pattern="%"+literal(search)+"%";
            return cb.and(predicate,cb.or(cb.like(cb.lower(root.get("username")),cb.lower(cb.literal(pattern)),'\\'),
                    cb.like(cb.lower(root.get("displayName")),cb.lower(cb.literal(pattern)),'\\')));
        };
        var pageable=PageRequest.of(page,size,Sort.by(Sort.Order.asc("username"),Sort.Order.asc("id")));
        // Project only candidate scalars, not managed accounts/credentials or stale L1 state.
        return accounts.findBy(eligible,query->query.as(AccountRepository.DriverCandidateView.class).page(pageable))
                .map(account->new Candidate(account.getId(),account.getUsername(),account.getDisplayName()));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Summary requireEligibleForBinding(UUID id) {
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Driver account binding requires a writable caller transaction");
        var account=id==null?null:accounts.findById(id).orElse(null);
        if(account==null)throw invalid();
        entityManager.refresh(account,LockModeType.PESSIMISTIC_WRITE);
        if(!eligible(account.role,account.enabled,account.mustChangePassword,account.temporaryPasswordExpiresAt,clock.instant()))throw invalid();
        return new Summary(account.id,account.username,account.displayName,true);
    }
    private static boolean eligible(AccountRole role,boolean enabled,boolean mustChange,Instant expires,Instant now) {
        return role==AccountRole.DRIVER&&enabled&&(!mustChange||expires!=null&&now.isBefore(expires));
    }
    private static UpmsException invalid(){return UpmsException.field("accountId","請選擇目前有效的 DRIVER 帳號");}
    private static String literal(String value){return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    private static String search(String raw) {
        if(raw==null)return "";
        for(int i=0;i<raw.length();i++) {
            char ch=raw.charAt(i);
            if(Character.isHighSurrogate(ch)) {
                if(i+1>=raw.length()||!Character.isLowSurrogate(raw.charAt(++i)))throw UpmsException.field("q","包含無效 Unicode 字元");
            }else if(Character.isLowSurrogate(ch))throw UpmsException.field("q","包含無效 Unicode 字元");
        }
        String value=Normalizer.normalize(raw,Normalizer.Form.NFC);int begin=0,end=value.length();
        while(begin<end&&space(value.codePointAt(begin)))begin+=Character.charCount(value.codePointAt(begin));
        while(end>begin&&space(value.codePointBefore(end)))end-=Character.charCount(value.codePointBefore(end));
        value=value.substring(begin,end);
        if(value.codePointCount(0,value.length())>100||value.codePoints().anyMatch(Character::isISOControl))
            throw UpmsException.field("q","查詢文字長度或內容不符合限制");
        return value;
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xFEFF;}
}
