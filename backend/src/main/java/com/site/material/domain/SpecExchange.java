package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 规格领错退换料单：班组现场发现材料规格不对无法安装，班组长发起退换，
 * 上报未使用数量（可退）、已切割数量（损耗）与现场照片；仓管确认时判定责任：
 *  - KEEPER 仓管发错：可退部分重新入库，已切割计损耗，班组不扣款（项目承担口径）；
 *  - CREW 班组申请错误：可退部分重新入库，已切割损耗成本归入该班组。
 * 状态：SUBMITTED -> CONFIRMED / REJECTED
 */
@Getter
@Setter
@Entity
@Table(name = "spec_exchange")
public class SpecExchange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @Column(nullable = false)
    private Long crewId;

    /** 关联原领料单（可空） */
    private Long requisitionId;

    @Column(nullable = false, length = 32)
    private String building;

    @Column(length = 64)
    private String area;

    @Column(length = 64)
    private String process;

    /** 领错的材料（错误规格材料） */
    @Column(nullable = false)
    private Long materialId;

    /** 领出批次（仓管发料批次，可退数量回补该批次） */
    private Long batchId;

    /** 实收（错误）规格快照 */
    @Column(length = 64)
    private String wrongSpec;

    /** 现场实际需要的规格 */
    @Column(length = 64)
    private String expectSpec;

    /** 班组长自报该批领出数量 */
    private Double issuedQty = 0d;

    /** 未使用、可退数量（仓管确认后回补批次库存） */
    @Column(nullable = false)
    private Double unusedQty = 0d;

    /** 已切割数量（无法安装，仓管确认后计入损耗） */
    @Column(nullable = false)
    private Double cutQty = 0d;

    /** 现场照片（data URI Base64，最多 3 张；@Lob 在 H2 映射 CLOB、MySQL 映射 LONGTEXT） */
    @Lob
    @Column
    private String photo1;
    @Lob
    @Column
    private String photo2;
    @Lob
    @Column
    private String photo3;

    /** SUBMITTED / CONFIRMED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status = "SUBMITTED";

    /** 责任归属（仓管确认时填写）：KEEPER 仓管发错 / CREW 班组申请错误 */
    @Column(length = 16)
    private String responsibility;

    /** 仓管责任判定说明 */
    private String faultNote;

    /** 确认后为已切割部分生成的损耗记录 ID */
    private Long wasteId;

    private String applicant;
    private String confirmedBy;
    private LocalDateTime applyTime;
    private LocalDateTime confirmTime;
    private String remark;

    public List<String> photoList() {
        List<String> xs = new ArrayList<>();
        if (photo1 != null) xs.add(photo1);
        if (photo2 != null) xs.add(photo2);
        if (photo3 != null) xs.add(photo3);
        return xs;
    }
}
