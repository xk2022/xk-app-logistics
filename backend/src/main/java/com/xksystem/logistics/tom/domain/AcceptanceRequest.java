package com.xksystem.logistics.tom.domain;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** New acceptance input only; the historical draft codec and schemas remain unchanged. */
public record AcceptanceRequest(long expectedVersion) {
    public static AcceptanceRequest parse(JsonNode original) {
        if(original==null||!original.isObject())throw TomDomainException.invalid("","request","請提供 JSON 物件");
        for(String property:original.propertyNames())if(!property.equals("expectedVersion"))
            throw TomDomainException.invalid("/"+escape(property),"request","此操作不接受其他欄位");
        JsonNode value=original.get("expectedVersion");
        if(value==null||!value.isNumber())throw invalid();
        try {
            BigDecimal number=value.decimalValue();
            // Bound before integer conversion or exponent expansion, exactly as the original TOM codec does.
            if(number.signum()<0||number.compareTo(BigDecimal.valueOf(DraftCodec.MAX_VERSION))>0
                    ||number.signum()!=0&&number.stripTrailingZeros().scale()>0)throw invalid();
            return new AcceptanceRequest(number.signum()==0?0:number.longValueExact());
        }catch(ArithmeticException|NumberFormatException failure){throw invalid();}
    }
    private static TomDomainException invalid(){return TomDomainException.invalid("/expectedVersion","AF01","請提供 0 至 9007199254740991 的整數版本");}
    private static String escape(String property){return property.replace("~","~0").replace("/","~1");}
}
