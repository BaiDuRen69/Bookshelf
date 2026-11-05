package com.bai.bookshelf.data.entity;

import androidx.room.Entity;
import androidx.room.Index;

import java.io.Serializable;

/**
 * 每日阅读打卡（打卡页一期）：一条 = 「这一天读过这本书」。
 *
 * <p>(bookId, dayKey) 复合主键天然去重，不再加自增 id 与唯一索引。
 * 打卡只写这张表，不改写 books.readStatus、不新建或结束阅读会话。
 *
 * <p>dayKey 用 {@link com.bai.bookshelf.util.DateKeys} 的 yyyyMMdd 整数口径，
 * 不用字符串，便于区间查询与跨月跨年计算。
 */
@Entity(tableName = "check_ins",
        primaryKeys = {"bookId", "dayKey"},
        indices = {@Index("dayKey")})
public class CheckIn implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 关联的书；删书时由 CheckInDao.deleteByBook 联动清理 */
    public long bookId;

    /** yyyyMMdd */
    public int dayKey;

    /** 实际点击时刻（毫秒） */
    public long createdAt;

    /** true = 打卡当天补的是过去某日 */
    public boolean backfill;

    /** 当天读到第几页，0 = 未填；本期只存不用 */
    public int pages;
}
