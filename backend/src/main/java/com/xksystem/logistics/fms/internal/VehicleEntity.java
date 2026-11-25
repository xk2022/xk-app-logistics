package com.xksystem.logistics.fms.internal;

import com.xksystem.logistics.fms.AdministrativeStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="fms_vehicle",schema="logistics")
class VehicleEntity {
    @Id UUID id;
    @Column(name="plate_no",nullable=false,unique=true,length=40) String plateNo;
    @Column(name="vehicle_type_id",nullable=false) UUID vehicleType;
    @Column(name="capacity_ton",columnDefinition="numeric") BigDecimal capacityTon;
    @Column(name="registration_no",length=100) String registrationNo;
    // Direct JDBC 4.2 LocalDate avoids java.sql.Date's hybrid Gregorian cutover.
    @JdbcTypeCode(SqlTypes.LOCAL_DATE) @Column(name="registration_date") LocalDate registrationDate;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE) @Column(name="inspection_due_date") LocalDate inspectionDueDate;
    @Enumerated(EnumType.STRING) @Column(name="administrative_status",nullable=false,length=16) AdministrativeStatus administrativeStatus;
    @Version long version;
    @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
    @Column(name="created_by",nullable=false,updatable=false) UUID createdBy;
    @Column(name="updated_at",nullable=false) Instant updatedAt;
    @Column(name="updated_by",nullable=false) UUID updatedBy;
    protected VehicleEntity() {}
}
