package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 国家/地区库：与书籍上是「名称快照」关系（书籍存字符串），
 * 重命名/删除时由调用方同步到所有书。
 */
@Entity(tableName = "countries")
public class Country implements Serializable {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;

    public int sort;
}
