package com.bai.bookshelf.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 藏书实体：对应 Room 表 books。
 * 实现 Serializable 是为了在 Activity 之间用 Intent 直接传递整本书。
 */
@Entity(tableName = "books")
public class Book implements Serializable {
    private static final long serialVersionUID = 1L;

    // ===== 藏书状态 stockStatus 取值 =====
    public static final int STOCK_IN = 0;      // 在库
    public static final int STOCK_LENT = 1;    // 借出
    public static final int STOCK_LOST = 2;    // 遗失

    // ===== 阅读状态 readStatus 取值 =====
    public static final int READ_NONE = 0;     // 未读
    public static final int READING = 1;       // 在读
    public static final int READ_DONE = 2;     // 已读

    // ===== 拥有类型 ownType 取值 =====
    public static final int OWN_BUY = 0;       // 自购
    public static final int OWN_BORROW = 1;    // 借阅
    public static final int OWN_GIFT = 2;      // 赠书

    @PrimaryKey(autoGenerate = true)
    public long id;

    // ---- 书目信息（ISBN API 自动补全的部分） ----
    public String isbn = "";          // ISBN-13，查重用
    public String title = "";         // 书名（必填）
    public String subtitle = "";      // 副标题
    public String author = "";        // 作者（多作者用、拼接）
    public String translator = "";    // 译者
    public String publisher = "";     // 出版社
    public String publishDate = "";   // 版次（如「2021年3月第1版」，原出版日期字段保留沿用）
    public int pages;                 // 页数
    public double listPrice;          // 定价
    public String coverUrl = "";      // 封面：网络链接或本地文件绝对路径（拍照/相册）
    public String summary = "";       // 内容简介

    // ---- v2 新增书目字段 ----
    @ColumnInfo(defaultValue = "")
    public String printing = "";      // 印次（如「2022年1月第3次印刷」）
    @ColumnInfo(defaultValue = "")
    public String country = "";       // 国家/地区（如 中国/美国/日本）
    @ColumnInfo(defaultValue = "")
    public String doubanRating = "";  // 豆瓣评分（手动填写，如 8.9）
    @ColumnInfo(defaultValue = "")
    public String wordCount = "";     // 字数（如 320千字）
    @ColumnInfo(defaultValue = "")
    public String category = "";      // 分类（如 小说/计算机/历史）
    @ColumnInfo(defaultValue = "")
    public String tags = "";          // 标签，多个用英文逗号分隔

    // ---- 个人管理信息 ----
    public Long locationId;           // 存放地点（可空）
    public int stockStatus = STOCK_IN;
    public int readStatus = READ_NONE;
    public int ownType = OWN_BUY;     // 来源大类：0自购 1借阅 2赠书
    @ColumnInfo(defaultValue = "")
    public String sourceChannel = ""; // 来源渠道（自定义列表，如 拼多多/淘宝/多抓鱼）
    public String purchaseDate = "";  // 购买日期 yyyy-MM-dd
    public double purchasePrice;      // 实付价格
    @ColumnInfo(defaultValue = "1")
    public int quantity = 1;          // 拥有数量（同款书持有几本）
    @ColumnInfo(defaultValue = "")
    public String locationDetail = ""; // 各本存放位置（多本时填写，如「2本书房/1本客厅」）
    public int rating;                // 个人评分 0-5
    public String note = "";          // 备注

    // ---- 元数据 ----
    public long createdAt;            // 录入时间戳
    public long updatedAt;            // 最后修改时间戳（二期同步用）
    public boolean dirty;             // 待同步标记（二期用）
}
