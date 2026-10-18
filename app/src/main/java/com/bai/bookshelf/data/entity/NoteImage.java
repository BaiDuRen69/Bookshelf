package com.bai.bookshelf.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 备注图片：任意「备注」字段（书级备注、逐本备注、借出备注）的图片附件。
 * 用 ownerKey 区分归属：
 * - "book:{bookId}"           书级备注
 * - "copy:{bookId}:{copyIndex}" 第 N 本逐本备注
 * - "borrow:{borrowRecordId}" 某条借出记录的备注
 *
 * 图片文件统一复制到应用私有目录 note_images/ 下，表里只存绝对路径快照。
 */
@Entity(tableName = "note_images")
public class NoteImage implements Serializable {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 所属书籍 id（清理时仍按书级删除最方便） */
    public long bookId;

    /** 归属键，格式见上方说明 */
    @ColumnInfo(defaultValue = "")
    public String ownerKey = "";

    /** 本地文件绝对路径 */
    @ColumnInfo(defaultValue = "")
    public String path = "";

    /** 排序（按添加顺序） */
    public int sort;
}
