package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.BookCopy;

import java.util.List;

/** 副本信息表访问：按书存取，保存时整批重建（先删后插）；详情页逐本借出/归还用 update */
@Dao
public interface BookCopyDao {

    /** 某本书的全部副本，按副本序号排序 */
    @Query("SELECT * FROM book_copies WHERE bookId = :bookId ORDER BY copyIndex ASC")
    List<BookCopy> getByBook(long bookId);

    /** 删除某本书的全部副本（保存前重建 / 删书联动清理） */
    @Query("DELETE FROM book_copies WHERE bookId = :bookId")
    void deleteByBook(long bookId);

    /** zip 备份恢复用：全部副本 */
    @Query("SELECT * FROM book_copies")
    List<BookCopy> getAll();

    /** zip 备份恢复用：清空副本表 */
    @Query("DELETE FROM book_copies")
    void deleteAll();

    @Insert
    void insertAll(List<BookCopy> copies);

    /** 插入单个副本（逐本借出时库里还没有这行的场景） */
    @Insert
    long insert(BookCopy copy);

    /** 更新单个副本（逐本借出/归还改状态） */
    @Update
    void update(BookCopy copy);
}
