package com.xksystem.logistics.tms.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

public class EvidenceFailure extends RuntimeException {
    private final int status;private final String code;private final Object data;
    public EvidenceFailure(int status,String code,String message,Object data){super(message);this.status=status;this.code=code;this.data=data;}
    public int status(){return status;} public String code(){return code;} public Object data(){return data;}
    public static EvidenceFailure invalid(String field,String message){return new EvidenceFailure(400,"E0400","請確認輸入欄位",Map.of("reason","VALIDATION_ERROR","fields",Map.of(field,message)));}
    public static EvidenceFailure missing(){return new EvidenceFailure(404,"E0404","找不到目前本人有效指派或證據",null);}
    public static EvidenceFailure conflict(String reason,String message){return conflict(reason,message,null);}
    public static EvidenceFailure conflict(String reason,String message,Long version){
        String code=switch(reason){case "VERSION_CONFLICT"->"E1801";case "INVALID_STATE"->"E1802";case "POLICY_NOT_CONFIRMED"->"E1803";
            case "IDEMPOTENCY_KEY_REUSED"->"E1804";case "IDEMPOTENCY_IN_PROGRESS"->"E1805";case "PROTECTED_RELATION"->"E1806";
            case "EVIDENCE_REQUIRED"->"E1807";case "EVIDENCE_INVALID"->"E1808";default->throw new IllegalArgumentException("Unknown evidence conflict");};
        var data=new LinkedHashMap<String,Object>();data.put("reason",reason);if(version!=null)data.put("actualVersion",version);
        return new EvidenceFailure(409,code,message,data);
    }
}
