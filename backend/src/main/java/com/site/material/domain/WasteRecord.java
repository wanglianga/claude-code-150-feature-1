package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 损耗记录：瓷砖破损、砂浆过期、保管不当等现场损耗核销。
 * 责任归属：CREW 班组责任(计入扣款) / SUPPLIER 供应商质量问题(索赔) /
 *          DESIGN 设计变更 / COMPANY 项目自身承担 / NATURE 不可抗力
 */
@Getter
@Setter
@Entity
@Table(name = "waste_record")
public class WasteRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @Column(nullable = false)
    private Long materialId;

    private Long batchId;
    private Long crewId;

    @Column(nullable = false, length = 32)
    private String building;

    @Column(length = 64)
    private String area;

    @Column(length = 64)
    private String process;

    @Column(nullable = false)
    private Double qty;

    private Double unitPrice = 0d;

    /** BREAKAGE 瓷砖破损 / EXPIRED 砂浆过期 / STORAGE 保管不当 /
     *  CONSTRUCTION 施工错误 / QUALITY 供应商质量 / CUT 切割损耗(规格领错退换) / OTHER */
    @Column(nullable = false, length = 16)
    private String wasteType;

    @Column(nullable = false, length = 16)
    private String responsibility = "COMPANY";

    /** 关联变更签证（设计变更类损耗） */
    private Long visaId;

    /** 关联退换单（规格领错退换自动产生的切割损耗） */
    private Long returnId;

    private String description;
    private String recorder;
    private LocalDateTime createdTime;
}
