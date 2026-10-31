package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.WishBook;

import java.util.List;

/** 想读清单（v4.48）：优先级高的在前，同优先级按加入时间倒序 */
@Dao
public interface WishBookDao {

    @Query("SELECT * FROM wish_books ORDER BY priority DESC, createdAt DESC")
    List<WishBook> getAll();

    /** 手动排序：按 sort 升序（新条目 sort 较大，自然排在末尾） */
    @Query("SELECT * FROM wish_books ORDER BY sort ASC, id ASC")
    List<WishBook> getAllManual();

    @Query("UPDATE wish_books SET sort = :sort WHERE id = :id")
    void updateSort(long id, int sort);

    @Insert
    long insert(WishBook wish);

    @Update
    void update(WishBook wish);

    @Query("DELETE FROM wish_books WHERE id = :id")
    void deleteById(long id);

    @Query("SELECT COUNT(*) FROM wish_books")
    int count();
}
