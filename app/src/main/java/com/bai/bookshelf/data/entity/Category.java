package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 分类库（v4.8 二级化）：一级/二级两级结构（Book.category 存所选分类名）。
 * parentId 为空 = 一级栏目；非空 = 指向所属一级栏目的 id（二级栏目）。
 * 与标签库同构，可新建/重命名/删除，重命名/删除会联动更新所有书籍上的分类名。
 * 注意：Book.category 只存名称字符串，因此全库任意层级内分类名不允许重复。
 */
@Entity(tableName = "categories")
public class Category implements Serializable {
    private static final long serialVersionUID = 1L;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";    // 分类名
    public int sort;            // 排序值
    public Long parentId;       // 所属一级分类 id；null = 一级栏目（v4.8 新增，可空列）
}
