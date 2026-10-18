package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 分类库：一本书只属于一个分类（Book.category 存分类名）。
 * 与标签库同构，在此统一维护，可新建/重命名/删除，
 * 重命名/删除会联动更新所有书籍上的分类名。
 */
@Entity(tableName = "categories")
public class Category implements Serializable {
    private static final long serialVersionUID = 1L;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";    // 分类名
    public int sort;            // 排序值
}
