package com.xksystem.logistics.adm;

import com.xksystem.logistics.upms.UpmsException;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

public record AdmPageQuery(PageRequest pageable, String q, Boolean enabled, String targetType, UUID targetId) {
    public static AdmPageQuery parse(MultiValueMap<String,String> query, String resource) {
        Set<String> fields = switch(resource) {
            case "categories" -> Set.of("sortOrder","code","name");
            case "items" -> Set.of("sortOrder","code","label");
            case "parameters" -> Set.of("key","name");
            case "logs" -> Set.of("occurredAt");
            default -> throw new IllegalArgumentException("Unknown ADM resource");
        };
        Set<String> allowed=resource.equals("logs") ? Set.of("page","size","sort","targetType","targetId")
                : Set.of("page","size","sort","q","enabled");
        query.forEach((key,values) -> {
            if (!allowed.contains(key) || values.size()!=1) throw UpmsException.field(key,"不支援此查詢欄位或重複參數");
        });
        int page=integer(query.getFirst("page"),0,0,1_000_000,"page");
        int size=integer(query.getFirst("size"),20,1,100,"size");
        String fallback=resource.equals("logs") ? "occurredAt,desc" : resource.equals("parameters") ? "key,asc" : "sortOrder,asc";
        String[] parts=Optional.ofNullable(query.getFirst("sort")).orElse(fallback).split(",",-1);
        if (parts.length!=2 || !fields.contains(parts[0]) || !Set.of("asc","desc").contains(parts[1]))
            throw UpmsException.field("sort","請使用支援的排序欄位與 asc 或 desc");
        var orders=new ArrayList<Sort.Order>();
        orders.add(new Sort.Order(parts[1].equals("asc")?Sort.Direction.ASC:Sort.Direction.DESC,parts[0]));
        if (resource.equals("logs")) orders.add(Sort.Order.desc("id"));
        else {
            String stable=resource.equals("parameters")?"key":"code";
            if (!parts[0].equals(stable)) orders.add(Sort.Order.asc(stable));
            orders.add(Sort.Order.asc("id"));
        }
        String text=AdmValidation.text(Optional.ofNullable(query.getFirst("q")).orElse(""),"q",0,100,true,false);
        Boolean enabled=null;
        if (query.containsKey("enabled")) {
            String value=query.getFirst("enabled");
            if (!Set.of("true","false").contains(value)) throw UpmsException.field("enabled","請提供 true 或 false");
            enabled=Boolean.valueOf(value);
        }
        String target=query.getFirst("targetType");
        if (target!=null && !Set.of("CATEGORY","ITEM","PARAMETER").contains(target)) throw UpmsException.field("targetType","請選擇有效目標類別");
        UUID targetId=null;
        if (query.containsKey("targetId")) {
            try { targetId=UUID.fromString(query.getFirst("targetId")); }
            catch(IllegalArgumentException e) { throw UpmsException.field("targetId","請提供有效 UUID"); }
        }
        return new AdmPageQuery(PageRequest.of(page,size,Sort.by(orders)),text,enabled,target,targetId);
    }
    private static int integer(String value,int fallback,int min,int max,String field) {
        if(value==null) return fallback;
        try {
            if(!value.matches("0|[1-9][0-9]{0,9}")) throw new NumberFormatException();
            int parsed=Integer.parseInt(value);
            if(parsed<min||parsed>max) throw new NumberFormatException();
            return parsed;
        } catch(NumberFormatException e) { throw UpmsException.field(field,"查詢數值超出允許範圍"); }
    }
}
