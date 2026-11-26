package com.xksystem.logistics.tms.execution;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final Object data;
    public ExecutionFailure(int status,String code,String message,Object data) {
        super(message);this.status=status;this.code=code;this.data=data;
    }
    public int status(){return status;} public String code(){return code;} public Object data(){return data;}
    public static ExecutionFailure missing(){return new ExecutionFailure(404,"E0404","找不到目前本人有效指派",null);}
    public static ExecutionFailure invalid(String field,String message){return new ExecutionFailure(400,"E0400","請確認輸入欄位",Map.of("reason","VALIDATION_ERROR","fields",Map.of(field,message)));}
    public static ExecutionFailure conflict(String reason,String message){return conflict(reason,message,null);}
    public static ExecutionFailure conflict(String reason,String message,Long actualVersion){
        String code=switch(reason){
            case "VERSION_CONFLICT"->"E1701";case "INVALID_STATE"->"E1702";
            case "POLICY_NOT_CONFIRMED"->"E1703";case "IDEMPOTENCY_KEY_REUSED"->"E1704";
            case "IDEMPOTENCY_IN_PROGRESS"->"E1705";case "PROTECTED_RELATION"->"E1706";
            default->throw new IllegalArgumentException("Unknown execution conflict");
        };
        var data=new LinkedHashMap<String,Object>();data.put("reason",reason);
        if(actualVersion!=null)data.put("actualVersion",actualVersion);
        return new ExecutionFailure(409,code,message,data);
    }
}
