package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Excerpt;

import java.util.List;

/** 摘抄表（v4.50）：按书取时间线，备份/搜索取全量 */
@Dao
public interface ExcerptDao {

    @Query("SELECT * FROM excerpts WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC")
    List<Excerpt> getByBook(long bookId);

    @Query("SELECT * FROM excerpts ORDER BY createdAt DESC, id DESC")
    List<Excerpt> getAll();

    @Query("SELECT COUNT(*) FROM excerpts WHERE bookId = :bookId")
    int countByBook(long bookId);

    @Insert
    long insert(Excerpt excerpt);

    @Update
    int update(Excerpt excerpt);

    @Query("DELETE FROM excerpts WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM excerpts WHERE bookId = :bookId")
    void deleteByBook(long bookId);

    @Query("DELETE FROM excerpts")
    void deleteAll();
}
