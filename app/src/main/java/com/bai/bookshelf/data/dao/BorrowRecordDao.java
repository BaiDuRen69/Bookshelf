package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.BorrowRecord;

import java.util.List;

/**
 * 借出记录数据访问对象。
 */
@Dao
public interface BorrowRecordDao {

    @Insert
    long insert(BorrowRecord record);

    @Update
    void update(BorrowRecord record);

    /** 查询某本书当前未归还的记录 */
    @Query("SELECT * FROM borrow_records WHERE bookId = :bookId AND status = 0 ORDER BY id DESC LIMIT 1")
    BorrowRecord findOpenByBook(long bookId);

    /** 某本书全部借出中的记录（多本逐本借出后，可能同时有多条） */
    @Query("SELECT * FROM borrow_records WHERE bookId = :bookId AND status = 0 ORDER BY copyIndex ASC")
    List<BorrowRecord> findOpenListByBook(long bookId);

    @Query("SELECT * FROM borrow_records WHERE bookId = :bookId ORDER BY id DESC")
    List<BorrowRecord> getByBook(long bookId);

    /** zip 备份恢复用：全部借出记录 */
    @Query("SELECT * FROM borrow_records")
    List<BorrowRecord> getAll();

    /** zip 备份恢复用：清空借出记录 */
    @Query("DELETE FROM borrow_records")
    void deleteAll();

    @Query("DELETE FROM borrow_records WHERE id = :id")
    void deleteById(long id);
}
