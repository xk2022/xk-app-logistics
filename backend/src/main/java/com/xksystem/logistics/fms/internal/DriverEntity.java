package com.xksystem.logistics.fms.internal;

import com.xksystem.logistics.fms.DutyStatus;
import com.xksystem.logistics.fms.EmploymentStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="fms_driver",schema="logistics")
class DriverEntity {
    @Id UUID id;
    @Column(name="driver_code",nullable=false,unique=true,length=40,updatable=false) String driverCode;
    @Column(nullable=false,length=200) String name;
    @Column(length=50) String phone;
    @Column(length=254) String email;
    @Column(name="account_id",unique=true) UUID accountId;
    @Enumerated(EnumType.STRING) @Column(name="employment_status",nullable=false,length=16) EmploymentStatus employmentStatus;
    @Enumerated(EnumType.STRING) @Column(name="duty_status",nullable=false,length=16) DutyStatus dutyStatus;
    @Column(name="license_type_id") UUID licenseType;
    @Column(name="license_reference",length=100) String licenseReference;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE) @Column(name="license_due_date") LocalDate licenseDueDate;
    @Version long version;
    @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
    @Column(name="created_by",nullable=false,updatable=false) UUID createdBy;
    @Column(name="updated_at",nullable=false) Instant updatedAt;
    @Column(name="updated_by",nullable=false) UUID updatedBy;
    protected DriverEntity() {}
}
