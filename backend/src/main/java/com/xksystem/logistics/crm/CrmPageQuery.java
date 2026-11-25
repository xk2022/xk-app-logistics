package com.xksystem.logistics.crm;

import com.xksystem.logistics.upms.UpmsException;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

public record CrmPageQuery(PageRequest pageable,String q,Boolean enabled) {
    public static CrmPageQuery parse(MultiValueMap<String,String> query,String resource) {
        Set<String> sortFields=switch(resource) {
            case "customers" -> Set.of("customerCode","name","createdAt","updatedAt");
            case "contacts" -> Set.of("name","title","createdAt","updatedAt");
            case "addresses" -> Set.of("label","addressType","createdAt","updatedAt");
            case "options" -> Set.of("customerCode","name");
            default -> throw new IllegalArgumentException("Unknown CRM resource");
        };
        Set<String> allowed=resource.equals("options")?Set.of("page","size","q","sort"):Set.of("page","size","q","enabled","sort");
        query.forEach((key,values)->{if(!allowed.contains(key)||values.size()!=1)throw UpmsException.field(key,"不支援此查詢欄位或重複參數");});
        int page=integer(query.getFirst("page"),0,0,1_000_000,"page");
        int size=integer(query.getFirst("size"),20,1,100,"size");
        String fallback=resource.equals("contacts")?"name,asc":resource.equals("addresses")?"createdAt,asc":"customerCode,asc";
        String[] sort=Optional.ofNullable(query.getFirst("sort")).orElse(fallback).split(",",-1);
        if(sort.length!=2||!sortFields.contains(sort[0])||!Set.of("asc","desc").contains(sort[1]))throw UpmsException.field("sort","請使用支援的排序欄位與 asc 或 desc");
        var primary=new Sort.Order(sort[1].equals("asc")?Sort.Direction.ASC:Sort.Direction.DESC,sort[0]);
        var pageable=PageRequest.of(page,size,Sort.by(primary,Sort.Order.asc("id")));
        String text=CrmValidation.text(Optional.ofNullable(query.getFirst("q")).orElse(""),"q",0,100,false);
        Boolean enabled=null;
        if(query.containsKey("enabled")) {
            String value=query.getFirst("enabled");
            if(!Set.of("true","false").contains(value))throw UpmsException.field("enabled","請提供 true 或 false");
            enabled=Boolean.valueOf(value);
        }
        return new CrmPageQuery(pageable,text==null?"":text,resource.equals("options")?Boolean.TRUE:enabled);
    }
    private static int integer(String value,int fallback,int min,int max,String field) {
        if(value==null)return fallback;
        try {
            if(!value.matches("0|[1-9][0-9]{0,9}"))throw new NumberFormatException();
            int parsed=Integer.parseInt(value);
            if(parsed<min||parsed>max)throw new NumberFormatException();return parsed;
        }catch(NumberFormatException e){throw UpmsException.field(field,"查詢數值超出允許範圍");}
    }
}
