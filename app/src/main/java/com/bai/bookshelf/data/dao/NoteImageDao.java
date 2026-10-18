package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.bai.bookshelf.data.entity.NoteImage;

import java.util.List;

/** 备注图片表访问：按书存取，保存时整批重建（先删后插） */
@Dao
public interface NoteImageDao {

    /** 某个 ownerKey 的全部备注图片，按添加顺序排序 */
    @Query("SELECT * FROM note_images WHERE ownerKey = :ownerKey ORDER BY sort ASC")
    List<NoteImage> getByOwnerKey(String ownerKey);

    /** 某本书的全部备注图片（含书级/逐本/借出记录），删书时取文件路径用 */
    @Query("SELECT * FROM note_images WHERE bookId = :bookId ORDER BY sort ASC")
    List<NoteImage> getByBook(long bookId);

    /** 删除某个 ownerKey 的全部备注图片记录（保存前重建） */
    @Query("DELETE FROM note_images WHERE ownerKey = :ownerKey")
    void deleteByOwnerKey(String ownerKey);

    /** 删除某本书的全部备注图片（删书联动清理） */
    @Query("DELETE FROM note_images WHERE bookId = :bookId")
    void deleteByBook(long bookId);

    /** zip 备份恢复用：全部备注图片记录 */
    @Query("SELECT * FROM note_images")
    List<NoteImage> getAll();

    /** zip 备份恢复用：清空备注图片记录 */
    @Query("DELETE FROM note_images")
    void deleteAll();

    @Insert
    long insert(NoteImage img);

    @Insert
    void insertAll(List<NoteImage> imgs);
}
