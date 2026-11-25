package com.xksystem.logistics.fms;

import com.xksystem.logistics.upms.UpmsException;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

public record VehiclePageQuery(PageRequest pageable,String q,UUID vehicleType,AdministrativeStatus administrativeStatus) {
    public static VehiclePageQuery parse(MultiValueMap<String,String> query,boolean options) {
        Set<String> allowed=options?Set.of("page","size","q","vehicleType"):Set.of("page","size","q","vehicleType","administrativeStatus");
        query.forEach((field,values)->{if(!allowed.contains(field)||values.size()!=1)throw UpmsException.field(field,"不支援此查詢欄位或重複參數");});
        int page=integer(query.getFirst("page"),0,0,100_000,"page");
        int size=integer(query.getFirst("size"),20,1,100,"size");
        String raw=query.containsKey("q")?query.getFirst("q"):"";
        String text=VehicleValidation.text(raw,"q",100);
        UUID type=query.containsKey("vehicleType")?VehicleValidation.uuid(query.getFirst("vehicleType"),"vehicleType"):null;
        AdministrativeStatus status=query.containsKey("administrativeStatus")?VehicleValidation.status(query.getFirst("administrativeStatus")):null;
        return new VehiclePageQuery(PageRequest.of(page,size,Sort.by(Sort.Order.asc("plateNo"),Sort.Order.asc("id"))),text==null?"":text,type,
                options?AdministrativeStatus.AVAILABLE:status);
    }
    private static int integer(String raw,int fallback,int min,int max,String field) {
        if(raw==null)return fallback;
        try {
            if(!raw.matches("0|[1-9][0-9]{0,9}"))throw new NumberFormatException();
            int value=Integer.parseInt(raw);if(value<min||value>max)throw new NumberFormatException();return value;
        }catch(NumberFormatException failure){throw UpmsException.field(field,"查詢數值超出允許範圍");}
    }
}
