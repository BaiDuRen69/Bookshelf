package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Location;

import java.util.List;

/**
 * 存放地点数据访问对象。
 */
@Dao
public interface LocationDao {

    @Query("SELECT * FROM locations ORDER BY sort ASC, id ASC")
    List<Location> getAll();

    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    Location getById(long id);

    @Insert
    long insert(Location location);

    @Update
    void update(Location location);

    @Query("DELETE FROM locations WHERE id = :id")
    void deleteById(long id);

    /** zip 备份恢复用：清空地点表 */
    @Query("DELETE FROM locations")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM locations")
    int count();

    // ---- 默认地点 ----
    @Query("UPDATE locations SET isDefault = 0")
    void clearDefault();

    @Query("UPDATE locations SET isDefault = 1 WHERE id = :id")
    void setDefault(long id);

    @Query("SELECT * FROM locations WHERE isDefault = 1 LIMIT 1")
    Location getDefault();
}
