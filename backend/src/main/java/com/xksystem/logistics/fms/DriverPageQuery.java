package com.xksystem.logistics.fms;

import com.xksystem.logistics.upms.UpmsException;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

public record DriverPageQuery(PageRequest pageable,String q,EmploymentStatus employmentStatus,DutyStatus dutyStatus,UUID licenseType) {
    public static DriverPageQuery parse(MultiValueMap<String,String> query,String resource) {
        Set<String> allowed=switch(resource) {
            case "drivers" -> Set.of("page","size","q","employmentStatus","dutyStatus","licenseType");
            case "options" -> Set.of("page","size","q","licenseType");
            case "accounts" -> Set.of("page","size","q");
            default -> throw new IllegalArgumentException("Unknown driver query resource");
        };
        query.forEach((field,values)->{if(!allowed.contains(field)||values.size()!=1)throw UpmsException.field(field,"不支援此查詢欄位或重複參數");});
        int page=integer(query.getFirst("page"),0,0,100_000,"page"),size=integer(query.getFirst("size"),20,1,100,"size");
        String text=DriverValidation.text(query.containsKey("q")?query.getFirst("q"):"","q",100);
        var employment=query.containsKey("employmentStatus")?DriverValidation.employmentStatus(query.getFirst("employmentStatus")):null;
        var duty=query.containsKey("dutyStatus")?DriverValidation.dutyStatus(query.getFirst("dutyStatus")):null;
        UUID type=query.containsKey("licenseType")?DriverValidation.uuid(query.getFirst("licenseType"),"licenseType"):null;
        return new DriverPageQuery(PageRequest.of(page,size,Sort.by(Sort.Order.asc(resource.equals("accounts")?"username":"driverCode"),Sort.Order.asc("id"))),
                text==null?"":text,resource.equals("options")?EmploymentStatus.ACTIVE:employment,resource.equals("options")?DutyStatus.ON_DUTY:duty,type);
    }
    private static int integer(String raw,int fallback,int minimum,int maximum,String field) {
        if(raw==null)return fallback;
        try {
            if(!raw.matches("0|[1-9][0-9]{0,9}"))throw new NumberFormatException();
            int value=Integer.parseInt(raw);if(value<minimum||value>maximum)throw new NumberFormatException();return value;
        }catch(NumberFormatException failure){throw UpmsException.field(field,"查詢數值超出允許範圍");}
    }
}
