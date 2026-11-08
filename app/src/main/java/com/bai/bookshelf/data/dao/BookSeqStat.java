package com.bai.bookshelf.data.dao;

/**
 * 按「书 + 轮次」聚合的打卡天数（v5.9.2 第 7 项）。
 *
 * <p>打卡页原先为了算每本书的已记天数把整张 check_ins 全取出来再逐行数；
 * 现在只按当前在读的几十本书一次分组，天数的口径不变（不同 dayKey 的个数）。
 */
public class BookSeqStat {
    public long bookId;
    public int seq;
    public int dayCount;
}
