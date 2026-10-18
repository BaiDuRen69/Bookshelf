package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 标签库：微信式标签，一本书可挂多个（Book.tags 存逗号分隔的标签名）。
 * 标签在此统一维护，可重命名/删除。
 */
@Entity(tableName = "tags")
public class Tag implements Serializable {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";    // 标签名
    public int sort;            // 排序值
}
