package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 退换料现场照片：班组长发起规格领错退换时上传，仓管确认时核对。
 * 以 dataURL（base64）形式落库，演示环境无需独立文件存储。
 */
@Getter
@Setter
@Entity
@Table(name = "return_photo")
public class ReturnPhoto {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id")
    private MaterialReturn materialReturn;

    /** 原始文件名 */
    private String name;

    /** data:image/...;base64,... 形式的图片内容 */
    @Lob
    @Column(nullable = false)
    private String dataUrl;
}
