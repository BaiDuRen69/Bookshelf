package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 借出记录：一本书可有多条历史记录，status 标记是否已归还。
 */
@Entity(tableName = "borrow_records")
public class BorrowRecord {

    public static final int STATUS_OUT = 0;      // 借出中
    public static final int STATUS_RETURNED = 1; // 已归还

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long bookId;            // 关联的藏书 id
    public String borrower = "";   // 借给谁
    public String lendDate = "";   // 借出日期 yyyy-MM-dd
    public String dueDate = "";    // 应还日期（二期提醒用）
    public String returnDate = ""; // 实际归还日期
    public String note = "";       // 借出备注（借出原因、联系方式等）
    public int status = STATUS_OUT;
    public int copyIndex = 1;      // 借出的是第几本（1 = 第 1 本/主记录；多本时逐本借出）
}
