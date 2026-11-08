package com.bai.bookshelf.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.bai.bookshelf.data.entity.CheckIn;

import java.util.List;

/** 打卡表：写入靠复合主键 + IGNORE 幂等，读取全部走区间/单日/单书查询。 */
@Dao
public interface CheckInDao {

    /** 重复 (bookId, dayKey) 静默忽略，返回 -1；不抛异常 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(CheckIn c);

    /** 按复合主键删一条（撤销打卡） */
    @Delete
    void delete(CheckIn c);

    /** 删书联动清理 */
    @Query("DELETE FROM check_ins WHERE bookId = :bookId")
    void deleteByBook(long bookId);

    /** 当天明细里撤销已删除书籍的孤儿记录：没有 Book 可走 toggle，只能按主键删 */
    @Query("DELETE FROM check_ins WHERE bookId = :bookId AND dayKey = :dayKey")
    int deleteByBookAndDay(long bookId, int dayKey);

    /** 月/年视图取数，闭区间 */
    @Query("SELECT * FROM check_ins WHERE dayKey BETWEEN :startKey AND :endKey "
            + "ORDER BY dayKey ASC, bookId ASC")
    List<CheckIn> getInRange(int startKey, int endKey);

    @Query("SELECT * FROM check_ins WHERE dayKey = :dayKey ORDER BY bookId ASC")
    List<CheckIn> getByDay(int dayKey);

    @Query("SELECT * FROM check_ins WHERE bookId = :bookId ORDER BY dayKey DESC")
    List<CheckIn> getByBook(long bookId);

    @Query("SELECT COUNT(*) FROM check_ins")
    int countAll();

    /** zip 备份用：全量导出（按日期升序，包内容稳定） */
    @Query("SELECT * FROM check_ins ORDER BY dayKey ASC, bookId ASC")
    List<CheckIn> getAll();

    /** zip 恢复用：一次事务内批量写回，重复主键按 IGNORE 跳过 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<CheckIn> list);

    /** zip 恢复用：清空打卡表（老备份没有这个键时也要清，避免残留） */
    @Query("DELETE FROM check_ins")
    void deleteAll();

    // ===== 精确阅读记录（v26）：日集合的唯一真相就是这张表，按 (bookId, seq) 取 =====

    /** 某一轮阅读的全部打卡，日期升序（画日历、算 readDays 都吃这个） */
    @Query("SELECT * FROM check_ins WHERE bookId = :bookId AND seq = :seq ORDER BY dayKey ASC")
    List<CheckIn> getByBookSeq(long bookId, int seq);

    /** 只要日期，避免为了数天数把整行读出来 */
    @Query("SELECT dayKey FROM check_ins WHERE bookId = :bookId AND seq = :seq")
    List<Integer> getDayKeys(long bookId, int seq);

    // ===== v5.9.2 第 7 项：打卡页不再全表取行，改按书/轮次聚合 =====

    /** 某本书某一轮已记几天（不同日期数）；空轮返回 0 */
    @Query("SELECT COUNT(DISTINCT dayKey) FROM check_ins WHERE bookId = :bookId AND seq = :seq")
    int countRoundDays(long bookId, int seq);

    /** 一本书一次拿到所有轮次的天数；bookIds 不能为空（空列表由调用方先短路） */
    @Query("SELECT bookId AS bookId, seq AS seq, COUNT(DISTINCT dayKey) AS dayCount "
            + "FROM check_ins WHERE bookId IN (:bookIds) GROUP BY bookId, seq")
    List<BookSeqStat> statsForBooks(List<Long> bookIds);

    /** 全部打卡过的自然日（截至今天）：连续天数只要这一列，不读整行 */
    @Query("SELECT DISTINCT dayKey FROM check_ins WHERE dayKey <= :todayKey")
    List<Integer> distinctDaysUpTo(int todayKey);

    /** 当天有几条记录（今天是否打过卡） */
    @Query("SELECT COUNT(*) FROM check_ins WHERE dayKey = :dayKey")
    int countOnDay(int dayKey);

    /** 跨轮移动：把指定几天挪到另一轮（编辑页「这段其实属于第二轮」） */
    @Query("UPDATE check_ins SET seq = :toSeq WHERE bookId = :bookId AND dayKey IN (:keys)")
    void updateSeq(long bookId, List<Integer> keys, int toSeq);

    /** 清空某一轮的日集合（模式切换、撤销重做用） */
    @Query("DELETE FROM check_ins WHERE bookId = :bookId AND seq = :seq")
    void deleteByBookSeq(long bookId, int seq);
}
