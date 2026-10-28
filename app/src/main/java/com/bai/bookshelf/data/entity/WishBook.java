package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 想读清单（v4.48）：还没买/还没借到的书。
 * 买到后用「转为藏书」把书名/作者/ISBN/心理价带进录入页，再删掉这一条。
 */
@Entity(tableName = "wish_books")
public class WishBook {

    public static final int PRIORITY_LOW = 0;
    public static final int PRIORITY_MID = 1;
    public static final int PRIORITY_HIGH = 2;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String title = "";
    public String author = "";
    public String isbn = "";
    public int priority = PRIORITY_MID;
    public double expectedPrice;   // 心理价（元），0 = 没填
    public String note = "";
    public long createdAt;
    public long updatedAt;
}
