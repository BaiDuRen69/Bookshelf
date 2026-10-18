package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 出版社库：编辑页快速选择，支持新建/重命名/删除。
 * 重命名/删除时同步更新所有书籍的 publisher 字段（名称快照）。
 */
@Entity(tableName = "publishers")
public class Publisher implements Serializable {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name = "";
    public int sort;
}
