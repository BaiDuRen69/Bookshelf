package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Publisher;

import java.util.List;

@Dao
public interface PublisherDao {

    @Query("SELECT * FROM publishers ORDER BY sort ASC, id ASC")
    List<Publisher> getAll();

    @Query("SELECT COUNT(*) FROM publishers")
    int count();

    @Query("SELECT * FROM publishers WHERE name = :name LIMIT 1")
    Publisher getByName(String name);

    @Insert
    long insert(Publisher publisher);

    @Update
    void update(Publisher publisher);

    @Query("DELETE FROM publishers WHERE id = :id")
    void deleteById(long id);

    /** zip 备份恢复用：清空出版社库 */
    @Query("DELETE FROM publishers")
    void deleteAll();
}
