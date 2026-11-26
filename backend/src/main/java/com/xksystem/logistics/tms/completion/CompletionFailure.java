package com.xksystem.logistics.tms.completion;
import java.util.Map;
public class CompletionFailure extends RuntimeException {
    private final int status;private final String code;private final Object data;
    public CompletionFailure(int status,String code,String message,Object data){super(message);this.status=status;this.code=code;this.data=data;}
    public int status(){return status;}public String code(){return code;}public Object data(){return data;}
    public static CompletionFailure missing(){return new CompletionFailure(404,"E0404","找不到授權範圍內的任務",null);}
    public static CompletionFailure invalid(String field,String message){return new CompletionFailure(400,"E0400","請確認輸入欄位",Map.of("reason","VALIDATION_ERROR","fields",Map.of(field,message)));}
    public static CompletionFailure version(long actual){return new CompletionFailure(409,"E2001","執行版本已更新，請重新讀取",Map.of("reason","VERSION_CONFLICT","actualVersion",actual));}
    public static CompletionFailure conflict(String reason,String message){String code=switch(reason){
        case "INVALID_STATE"->"E2002";case "POLICY_NOT_CONFIRMED"->"E2003";case "IDEMPOTENCY_KEY_REUSED"->"E2004";
        case "IDEMPOTENCY_IN_PROGRESS"->"E2005";case "PROTECTED_RELATION"->"E2006";case "EVIDENCE_REQUIRED"->"E2007";
        case "EVIDENCE_INVALID"->"E2008";case "UNRESOLVED_EXCEPTIONS"->"E2009";default->throw new IllegalArgumentException("Unknown completion conflict");};
        return new CompletionFailure(409,code,message,Map.of("reason",reason));}
}
