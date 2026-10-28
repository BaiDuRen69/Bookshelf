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

    @Insert
    long insert(WishBook wish);

    @Update
    void update(WishBook wish);

    @Query("DELETE FROM wish_books WHERE id = :id")
    void deleteById(long id);

    @Query("SELECT COUNT(*) FROM wish_books")
    int count();
}
