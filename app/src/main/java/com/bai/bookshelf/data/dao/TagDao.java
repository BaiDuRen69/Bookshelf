package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Tag;

import java.util.List;

/**
 * 标签库数据访问对象。
 */
@Dao
public interface TagDao {

    @Query("SELECT * FROM tags ORDER BY sort ASC, id ASC")
    List<Tag> getAll();

    @Insert
    long insert(Tag tag);

    @Update
    void update(Tag tag);

    @Query("DELETE FROM tags WHERE id = :id")
    void deleteById(long id);

    /** zip 备份恢复用：清空标签库 */
    @Query("DELETE FROM tags")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM tags")
    int count();
}
