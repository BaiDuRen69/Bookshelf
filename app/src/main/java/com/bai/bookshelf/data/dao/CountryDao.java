package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Country;

import java.util.List;

/** 国家/地区库访问 */
@Dao
public interface CountryDao {

    @Query("SELECT * FROM countries ORDER BY sort ASC")
    List<Country> getAll();

    @Insert
    long insert(Country country);

    @Update
    void update(Country country);

    @Delete
    void delete(Country country);

    @Query("DELETE FROM countries WHERE id = :id")
    void deleteById(long id);

    /** zip 备份恢复用：清空国家/地区库 */
    @Query("DELETE FROM countries")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM countries")
    int count();
}
