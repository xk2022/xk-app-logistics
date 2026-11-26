package com.xksystem.logistics.tms;

import java.util.LinkedHashMap;
import java.util.Map;

public class TmsFailure extends RuntimeException {
    private final int status;private final String code;private final Object data;
    public TmsFailure(int status,String code,String message,Object data){super(message);this.status=status;this.code=code;this.data=data;}
    public int status(){return status;} public String code(){return code;} public Object data(){return data;}
    public static TmsFailure invalid(String field,String message){return new TmsFailure(400,"E0400","請確認輸入欄位",Map.of("reason","VALIDATION_ERROR","fields",Map.of(field,message)));}
    public static TmsFailure missing(){return new TmsFailure(404,"E0404","找不到授權範圍內的任務或派工",null);}
    public static TmsFailure conflict(String reason,String message){return conflict(reason,message,null);}
    public static TmsFailure conflict(String reason,String message,Long generation){
        String code=switch(reason){case "VERSION_CONFLICT"->"E1601";case "RESOURCE_OCCUPIED"->"E1602";case "INVALID_STATE"->"E1603";
            case "PREREQUISITE_NOT_READY"->"E1604";case "POLICY_NOT_CONFIRMED"->"E1605";case "MASTER_UNAVAILABLE"->"E1606";
            case "IDEMPOTENCY_KEY_REUSED"->"E1607";case "IDEMPOTENCY_IN_PROGRESS"->"E1608";case "PROTECTED_RELATION"->"E1609";
            default->throw new IllegalArgumentException("Unknown TMS conflict");};
        var data=new LinkedHashMap<String,Object>();data.put("reason",reason);if(generation!=null)data.put("actualGeneration",generation);
        return new TmsFailure(409,code,message,data);
    }
}
