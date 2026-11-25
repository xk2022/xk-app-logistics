package com.xksystem.logistics.upms;

import java.util.Map;

public class UpmsException extends RuntimeException {
    private final int status;
    private final Object data;
    public UpmsException(int status, String message) { this(status, message, null); }
    public UpmsException(int status, String message, Object data) {
        super(message); this.status = status; this.data = data;
    }
    public int status() { return status; }
    public Object data() { return data; }
    public String code() { return "E0" + status; }
    public static UpmsException unauthorized() { return new UpmsException(401, "無法登入或登入已失效，請確認資料後重新登入"); }
    public static UpmsException forbidden() { return new UpmsException(403, "無權執行此操作，請確認權限或重新整理後再試"); }
    public static UpmsException field(String field, String message) { return new UpmsException(400, "請確認輸入欄位", Map.of(field, message)); }
}
