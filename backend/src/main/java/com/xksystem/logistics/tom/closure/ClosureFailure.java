package com.xksystem.logistics.tom.closure;
import java.util.Map;
public class ClosureFailure extends RuntimeException {
 private final int status;private final String code;private final Object data;
 public ClosureFailure(int status,String code,String message,Object data){super(message);this.status=status;this.code=code;this.data=data;}
 public int status(){return status;}public String code(){return code;}public Object data(){return data;}
 public static ClosureFailure missing(){return new ClosureFailure(404,"E0404","找不到授權範圍內的管理紀錄",null);}
 public static ClosureFailure invalid(String field,String message){return new ClosureFailure(400,"E0400","請確認輸入欄位",Map.of("reason","VALIDATION_ERROR","fields",Map.of(field,message)));}
 public static ClosureFailure version(long actual,boolean confirmation){return new ClosureFailure(409,"E2101","資料版本已更新，請重新讀取",Map.of("reason","VERSION_CONFLICT",confirmation?"actualConfirmationVersion":"actualOrderVersion",actual));}
 public static ClosureFailure conflict(String reason,String message){String code=switch(reason){case "INVALID_STATE"->"E2102";case "POLICY_NOT_CONFIRMED"->"E2103";case "IDEMPOTENCY_KEY_REUSED"->"E2104";case "IDEMPOTENCY_IN_PROGRESS"->"E2105";case "PROTECTED_RELATION"->"E2106";case "TASKS_INCOMPLETE"->"E2107";case "CONFIRMATION_REQUIRED"->"E2108";case "UNRESOLVED_EXCEPTIONS"->"E2109";case "RESOURCES_OCCUPIED"->"E2110";default->throw new IllegalArgumentException("Unknown management conflict");};return new ClosureFailure(409,code,message,Map.of("reason",reason));}
 public static ClosureFailure corrupt(){return new ClosureFailure(500,"E0500","管理紀錄暫時無法驗證",null);}
}
