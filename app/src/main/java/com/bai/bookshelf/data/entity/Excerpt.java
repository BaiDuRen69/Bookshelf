package com.bai.bookshelf.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 摘抄（v4.50）：一本书可以有很多条，按时间倒序展示成时间线。
 * page=0 表示没标页码；tags 与书籍标签一样用英文逗号分隔。
 * 图片统一落在 filesDir/excerpt_images/，表里只存绝对路径。
 */
@Entity(tableName = "excerpts")
public class Excerpt implements Serializable {
    private static final long serialVersionUID = 1L;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long bookId;

    @ColumnInfo(defaultValue = "0")
    public int page;

    @ColumnInfo(defaultValue = "")
    public String content = "";   // 原文/金句（必填）

    @ColumnInfo(defaultValue = "")
    public String note = "";      // 自己的批注/感想

    @ColumnInfo(defaultValue = "")
    public String tags = "";      // 逗号分隔

    @ColumnInfo(defaultValue = "")
    public String imagePath = ""; // 本地图片绝对路径，空=无

    public long createdAt;

    public long updatedAt;

    public boolean hasImage() {
        return imagePath != null && !imagePath.isEmpty();
    }

    public boolean hasNote() {
        return note != null && !note.trim().isEmpty();
    }

    public boolean hasTags() {
        return tags != null && !tags.trim().isEmpty();
    }
}
