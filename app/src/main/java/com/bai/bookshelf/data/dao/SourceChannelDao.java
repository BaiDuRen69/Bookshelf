package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.SourceChannel;

import java.util.List;

/**
 * 来源渠道数据访问对象。
 */
@Dao
public interface SourceChannelDao {

    @Query("SELECT * FROM source_channels ORDER BY sort ASC, id ASC")
    List<SourceChannel> getAll();

    /** 按来源大类取二级选项（编辑页联动用） */
    @Query("SELECT * FROM source_channels WHERE ownType = :ownType ORDER BY sort ASC, id ASC")
    List<SourceChannel> getByType(int ownType);

    @Query("SELECT COUNT(*) FROM source_channels WHERE ownType = :ownType")
    int countByType(int ownType);

    @Insert
    long insert(SourceChannel channel);

    @Update
    void update(SourceChannel channel);

    @Query("DELETE FROM source_channels WHERE id = :id")
    void deleteById(long id);

    /** zip 备份恢复用：清空来源渠道表 */
    @Query("DELETE FROM source_channels")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM source_channels")
    int count();
}
