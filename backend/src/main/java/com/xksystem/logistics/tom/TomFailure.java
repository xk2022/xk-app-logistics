package com.xksystem.logistics.tom;

import com.xksystem.logistics.tom.domain.FieldIssue;
import java.util.List;

public class TomFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final Object data;
    private final List<FieldIssue> fieldErrors;
    public TomFailure(int status,String code,String message,Object data,List<FieldIssue> fieldErrors) {
        super(message);this.status=status;this.code=code;this.data=data;this.fieldErrors=List.copyOf(fieldErrors);
    }
    public TomFailure(int status,String code,String message){this(status,code,message,null,List.of());}
    public int status(){return status;}
    public String code(){return code;}
    public Object data(){return data;}
    public List<FieldIssue> fieldErrors(){return fieldErrors;}
}
