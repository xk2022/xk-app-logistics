package com.xksystem.logistics.adm.internal;

import com.xksystem.logistics.adm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="adm_parameter_definition",schema="logistics")
class DefinitionEntity {
 @Id @Column(name="parameter_key",length=64) String key;
 @Column(nullable=false,length=160) String name;
 @Enumerated(EnumType.STRING) @Column(name="value_type",nullable=false,length=16) ParameterType valueType;
 @Column(nullable=false,length=1000) String description;
 @Column(name="min_length") Integer minLength;
 @Column(name="max_length") Integer maxLength;
 @JdbcTypeCode(SqlTypes.JSON) @Column(name="allowed_values",nullable=false,columnDefinition="jsonb") List<Object> allowedValues;
 @Column(name="min_value",precision=38,scale=12) BigDecimal minValue;
 @Column(name="max_value",precision=38,scale=12) BigDecimal maxValue;
 @Column(name="decimal_scale") Integer decimalScale;
 protected DefinitionEntity() {}
 AdmDtos.Definition dto(){return new AdmDtos.Definition(key,name,valueType,description,minLength,maxLength,
 allowedValues==null?List.of():allowedValues.stream().map(value->(String)value).toList(),minValue==null?null:minValue.toPlainString(),
 maxValue==null?null:maxValue.toPlainString(),decimalScale);}
}
