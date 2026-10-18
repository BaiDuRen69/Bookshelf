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

    /**
     * 藏书表的内容指纹（v3.2 书架页按需刷新用）。
     *
     * <p>只做聚合运算、不返回行数据，比拉全表快一两个数量级。书架页回到前台时先用它判断
     * 数据有没有变过：没变就沿用内存里的列表，完全跳过 Spinner 重建与 RecyclerView 重绘。
     *
     * <p>覆盖三种变更：
     * <ul>
     *   <li><b>增删</b>：COUNT(*) 与 SUM(id) 变化；</li>
     *   <li><b>录入/修改时间</b>：MIN/MAX(createdAt) 变化；</li>
     *   <li><b>编辑字段</b>：SUM(pages)、SUM(CAST(listPrice*100 AS INTEGER))、SUM(状态与评分) 变化。</li>
     * </ul>
     * 纯文本字段（书名/作者等）的编辑可能不改变以上任何合计，这种情况下会误判为「未变化」
     * 而跳过刷新——但列表里该书的文字需要在编辑页返回时更新，因此编辑页返回路径另有兜底
     * （录入/编辑页返回 RESULT_OK 时书架页用 ActivityResultLauncher 标脏）。
     * <p>价格放大 100 倍再取整：直接 CAST(45.5 AS INTEGER) 与 CAST(45.9 AS INTEGER) 都是 45，
     * 几分钱的调价会漏掉（已实测复现）。
     */
    @Query("SELECT COUNT(*) || ':' || IFNULL(MIN(createdAt), 0) || ':' || IFNULL(MAX(createdAt), 0) "
            + "|| ':' || IFNULL(SUM(id), 0) || ':' || IFNULL(SUM(pages), 0) "
            + "|| ':' || IFNULL(SUM(CAST(listPrice * 100 AS INTEGER)), 0) "
            + "|| ':' || IFNULL(SUM(stockStatus + readStatus + ownType + rating), 0) "
            + "FROM books")
    String getContentFingerprint();
}
