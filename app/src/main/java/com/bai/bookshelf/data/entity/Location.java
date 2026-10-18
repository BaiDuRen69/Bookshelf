package com.bai.bookshelf.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 存放地点：支持多级（parentId 指向父级），如「书房 → 书架A → 第2层」。
 * isDefault 标记默认地点，录入新书时自动选中。
 */
@Entity(tableName = "locations")
public class Location implements Serializable {
    private static final long serialVersionUID = 1L;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";    // 地点名，如「书架A-第2层」
    public Long parentId;       // 父级地点 id，可空
    public int sort;            // 排序值

    @ColumnInfo(defaultValue = "0")
    public boolean isDefault;   // 是否为默认存放地点（全表最多一个）
}
