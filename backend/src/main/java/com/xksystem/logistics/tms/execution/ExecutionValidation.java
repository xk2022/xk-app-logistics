package com.xksystem.logistics.tms.execution;

import com.xksystem.logistics.tms.TmsFailure;
import com.xksystem.logistics.tms.TmsValidation;
import java.util.Set;
import java.util.UUID;

public final class ExecutionValidation {
    private ExecutionValidation() {}
    public record Command(long expectedVersion,String hash) {}
    public static Command command(byte[] raw) {
        try {
            var node=TmsValidation.parse(raw);
            if(!node.isObject())throw ExecutionFailure.invalid("request","請提供 JSON 物件");
            for(String field:node.propertyNames())if(!Set.of("expectedVersion").contains(field))
                throw ExecutionFailure.invalid(field,"不接受此欄位");
            if(!node.has("expectedVersion"))throw ExecutionFailure.invalid("expectedVersion","請提供執行版本");
            long version=TmsValidation.integer(node.get("expectedVersion"),"expectedVersion");
            return new Command(version,TmsValidation.hash(node));
        } catch(TmsFailure failure) { throw new ExecutionFailure(failure.status(),failure.code(),failure.getMessage(),failure.data()); }
    }
    public static UUID assignmentId(String raw) {
        try{return TmsValidation.uuid(raw,"assignmentId");}
        catch(TmsFailure failure){throw new ExecutionFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}
    }
    public static String key(String raw) {
        try{return TmsValidation.key(raw);}
        catch(TmsFailure failure){throw new ExecutionFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}
    }
}
