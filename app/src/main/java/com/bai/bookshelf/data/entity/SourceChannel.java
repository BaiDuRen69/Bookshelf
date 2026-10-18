package com.bai.bookshelf.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 来源二级选项：按来源大类（ownType）分组，用户可自主增删改。
 * 自购(0) → 购买渠道（拼多多/淘宝…）；借阅(1) → 借阅地（图书馆/朋友…）；赠书(2) → 赠送来源（鉴书团…）。
 * 书籍保存的是选项名称字符串。
 */
@Entity(tableName = "source_channels")
public class SourceChannel implements Serializable {
    private static final long serialVersionUID = 1L;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";    // 选项名
    public int sort;            // 排序值

    /** 所属来源大类，与 Book.ownType 对应：0自购 1借阅 2赠书 */
    @ColumnInfo(defaultValue = "0")
    public int ownType;
}
