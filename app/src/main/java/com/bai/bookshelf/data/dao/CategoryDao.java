package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Category;

import java.util.List;

/**
 * 分类库数据访问对象。
 */
@Dao
public interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sort ASC, id ASC")
    List<Category> getAll();

    @Insert
    long insert(Category category);

    @Update
    void update(Category category);

    @Query("DELETE FROM categories WHERE id = :id")
    void deleteById(long id);

    /** zip 备份恢复用：清空分类库 */
    @Query("DELETE FROM categories")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM categories")
    int count();
}
