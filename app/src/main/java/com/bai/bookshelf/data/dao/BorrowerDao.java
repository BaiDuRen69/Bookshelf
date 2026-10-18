package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Borrower;

import java.util.List;

/**
 * 常用借阅人数据访问对象。
 */
@Dao
public interface BorrowerDao {

    @Query("SELECT * FROM borrowers ORDER BY sort ASC, id ASC")
    List<Borrower> getAll();

    @Insert
    long insert(Borrower borrower);

    @Update
    void update(Borrower borrower);

    @Query("DELETE FROM borrowers WHERE id = :id")
    void deleteById(long id);

    /** zip 备份恢复用：清空常用借阅人 */
    @Query("DELETE FROM borrowers")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM borrowers")
    int count();
}
