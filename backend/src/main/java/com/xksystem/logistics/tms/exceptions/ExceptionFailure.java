package com.xksystem.logistics.tms.exceptions;

import java.util.LinkedHashMap;
import java.util.Map;
public class ExceptionFailure extends RuntimeException {
    private final int status;private final String code;private final Object data;
    public ExceptionFailure(int status,String code,String message,Object data){super(message);this.status=status;this.code=code;this.data=data;}
    public int status(){return status;}public String code(){return code;}public Object data(){return data;}
    public static ExceptionFailure missing(){return new ExceptionFailure(404,"E0404","找不到授權範圍內的異常或補件",null);}
    public static ExceptionFailure invalid(String field,String message){return new ExceptionFailure(400,"E0400","請確認輸入欄位",Map.of("reason","VALIDATION_ERROR","fields",Map.of(field,message)));}
    public static ExceptionFailure conflict(String reason,String message){return conflict(reason,message,null,false);}
    public static ExceptionFailure version(long actual,boolean execution){return conflict("VERSION_CONFLICT","資料版本已更新，請重新讀取",actual,execution);}
    private static ExceptionFailure conflict(String reason,String message,Long actual,boolean execution){
        String code=switch(reason){case "VERSION_CONFLICT"->"E1901";case "INVALID_STATE"->"E1902";case "POLICY_NOT_CONFIRMED"->"E1903";
            case "IDEMPOTENCY_KEY_REUSED"->"E1904";case "IDEMPOTENCY_IN_PROGRESS"->"E1905";case "PROTECTED_RELATION"->"E1906";
            case "SUPPLEMENT_REQUIRED"->"E1907";case "EVIDENCE_INVALID"->"E1908";default->throw new IllegalArgumentException("Unknown exception conflict");};
        var data=new LinkedHashMap<String,Object>();data.put("reason",reason);if(actual!=null)data.put(execution?"actualExecutionVersion":"actualVersion",actual);
        return new ExceptionFailure(409,code,message,data);
    }
}
