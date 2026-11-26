package com.xksystem.logistics.tms;

import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

public record TmsQuery(int page,int size,String q,String state,UUID orderId,UUID taskId,ResourcePosition position,UUID assignmentId){
    public static TmsQuery parse(MultiValueMap<String,String> query,String resource){
        Set<String> allowed=switch(resource){case "tasks"->Set.of("page","size","q","state","orderId");case "resources"->Set.of("page","size","q","taskId","position","assignmentId");case "self"->Set.of("page","size");case "history"->Set.of("historyPage","historySize");default->throw new IllegalArgumentException("Unknown TMS query");};
        query.forEach((key,values)->{if(!allowed.contains(key)||values==null||values.size()!=1)throw TmsFailure.invalid(key,"不接受未知或重複查詢");});
        int page=number(query.getFirst(resource.equals("history")?"historyPage":"page"),0,0,100_000,"page");
        int size=number(query.getFirst(resource.equals("history")?"historySize":"size"),20,1,100,"size");
        String text=TmsValidation.text(query.containsKey("q")?query.getFirst("q"):"","q",100,false);
        String state=query.containsKey("state")?query.getFirst("state"):"UNASSIGNED";
        if(!Set.of("UNASSIGNED","ASSIGNED","ALL").contains(state))throw TmsFailure.invalid("state","請提供合法派工狀態篩選");
        UUID order=query.containsKey("orderId")?TmsValidation.uuid(query.getFirst("orderId"),"orderId"):null;
        UUID task=null,assignment=null;ResourcePosition position=null;
        if(resource.equals("resources")){task=TmsValidation.uuid(query.getFirst("taskId"),"taskId");try{position=ResourcePosition.valueOf(query.getFirst("position"));}catch(IllegalArgumentException|NullPointerException failure){throw TmsFailure.invalid("position","請提供 DRIVER、MAIN 或 TRAILER");}
            if(query.containsKey("assignmentId"))assignment=TmsValidation.uuid(query.getFirst("assignmentId"),"assignmentId");}
        return new TmsQuery(page,size,text,state,order,task,position,assignment);
    }
    private static int number(String raw,int fallback,int minimum,int maximum,String field){if(raw==null)return fallback;try{if(!raw.matches("0|[1-9][0-9]{0,9}"))throw new NumberFormatException();int value=Integer.parseInt(raw);if(value<minimum||value>maximum)throw new NumberFormatException();return value;}catch(NumberFormatException failure){throw TmsFailure.invalid(field,"查詢數值超出範圍");}}
}
