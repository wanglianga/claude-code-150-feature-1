package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "return_item")
public class ReturnItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id")
    private MaterialReturn materialReturn;

    @Column(nullable = false)
    private Long materialId;

    /** 退回的来源批次 */
    private Long batchId;

    @Column(nullable = false)
    private Double qty;

    /** 已切割数量（规格领错退换）：不可再发，确认时计入损耗，责任按 faultType 归属 */
    @Column(nullable = false)
    private Double cutQty = 0d;

    /** 退回时完好程度：GOOD 可重新发料 / DAMAGED 损坏降级 */
    @Column(length = 16)
    private String conditionType = "GOOD";

    private Double price = 0d;
}
