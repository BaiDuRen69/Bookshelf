package com.bai.bookshelf.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 同一本书的「第 N 本」副本信息（拥有数量 > 1 时使用）。
 * 第 1 本的信息仍放在 books 表主记录上，本表只存第 2 本及以后的副本，
 * copyIndex 从 2 开始，与展示文案「第 N 本」一致。
 */
@Entity(tableName = "book_copies")
public class BookCopy implements Serializable {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 所属书籍 id */
    public long bookId;

    /** 副本序号（从 2 开始；第 1 本是主记录） */
    public int copyIndex;

    /** 藏书状态（与 Book.stockStatus 同枚举） */
    public int stockStatus;

    /** 存放地点 id（可空） */
    public Long locationId;

    /** 来源大类（与 Book.ownType 同枚举：0 自购 / 1 借阅 / 2 赠书） */
    public int ownType;

    /** 来源渠道名（快照字符串，与书籍主记录一致的做法） */
    @ColumnInfo(defaultValue = "")
    public String sourceChannel = "";

    /** 购买日期 yyyy-MM-dd */
    @ColumnInfo(defaultValue = "")
    public String purchaseDate = "";

    /** 实付价格 */
    public double purchasePrice;

    /** 该本的备注（v9 起支持逐本备注） */
    @ColumnInfo(defaultValue = "")
    public String note = "";
}
