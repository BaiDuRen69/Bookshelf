package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 常用借阅人：借书时快速选择，可新建/重命名/删除（结构同标签库）。
 */
@Entity(tableName = "borrowers")
public class Borrower implements Serializable {
    private static final long serialVersionUID = 1L;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";    // 借阅人姓名/称呼
    @androidx.room.ColumnInfo(defaultValue = "")
    public String phone = "";   // v4.42：联系方式（电话/微信等，单一字段，催还时可直接复制）
    public int sort;            // 排序值
}
