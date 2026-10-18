package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.bai.bookshelf.data.entity.Book;

import java.util.List;

/**
 * 藏书表数据访问对象。
 * 个人藏书量不大（千级以内），列表筛选/排序统一在内存里做，SQL 只提供基础查询。
 */
@Dao
public interface BookDao {

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    List<Book> getAll();

    /**
     * 藏书表的响应式查询（v4.2，评审 #7）：books 表任何增删改都会自动推送最新列表，
     * 书架页据此驱动刷新——原先靠「内容指纹比对 + RESULT_OK 标脏 + 视图重建补偿」
     * 维持的手工刷新状态机可以整体删除，且纯文本字段（书名/作者）编辑也会触发推送
     * （指纹机制的已知漏洞被天然修复）。
     */
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    androidx.lifecycle.LiveData<List<Book>> getAllLive();

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    Book getById(long id);

    /** 按 ISBN 查重，返回 null 表示未录入 */
    @Query("SELECT * FROM books WHERE isbn = :isbn AND isbn != '' LIMIT 1")
    Book getByIsbn(String isbn);

    @Insert
    long insert(Book book);

    @Update
    int update(Book book);

    @Delete
    void delete(Book book);

    @Query("DELETE FROM books WHERE id = :id")
    void deleteById(long id);

    @Query("SELECT COUNT(*) FROM books")
    int count();

    /** zip 备份恢复用：清空藏书表（保留自增计数重置由 Room 负责） */
    @Query("DELETE FROM books")
    void deleteAll();

    /** 删除地点时，把引用该地点的书重置为未设置 */
    @Query("UPDATE books SET locationId = NULL WHERE locationId = :locationId")
    void clearLocation(long locationId);

    // ==================== 书架页加载优化（v3.2） ====================
    // 原来国家/来源渠道的去重是在 Java 里用 List.contains() 循环做的，O(n²)；
    // 交给 SQL 的 DISTINCT 直接出结果，且不再把整张 books 表读进内存做聚合。

    /**
     * 藏书里出现过的国家/地区（去重、非空、按名称排序），用于筛选下拉。
     *
     * <p>注意：必须用子查询让 DISTINCT 作用于 TRIM 的结果——直接写
     * {@code SELECT DISTINCT TRIM(country)} 时，DISTINCT 比较的是<b>原始列</b>，
     * 「中国」与「 中国」会被当成两条不同记录（已实测复现）。
     */
    @Query("SELECT DISTINCT country FROM (SELECT TRIM(country) AS country FROM books "
            + "WHERE country IS NOT NULL AND TRIM(country) != '') "
            + "ORDER BY country ASC")
    List<String> getDistinctCountries();

    /**
     * 藏书里出现过的来源渠道（去重、非空、按首次录入顺序），用于筛选下拉。
     *
     * <p>同样用子查询保证去重作用在 TRIM 之后；按 MIN(createdAt) 保持与旧实现
     * 一致的「首次录入顺序」。
     */
    @Query("SELECT channel FROM (SELECT TRIM(sourceChannel) AS channel, MIN(createdAt) AS firstAt "
            + "FROM books WHERE sourceChannel IS NOT NULL AND TRIM(sourceChannel) != '' "
            + "GROUP BY TRIM(sourceChannel)) ORDER BY firstAt ASC")
    List<String> getDistinctChannels();

    // v4.2：原 v3.2 的 getContentFingerprint() 内容指纹查询已删除——书架页改用
    // getAllLive() 响应式观察，Room 只在 books 表真正变化时推送，天然取代指纹判断，
    // 且纯文本字段（书名/作者）编辑也会触发推送（指纹机制的已知漏洞不复存在）。
}
