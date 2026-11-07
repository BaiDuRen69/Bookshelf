package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ReadDays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一本书各轮阅读的「日集合」暂存区（v5.9.1 第四步）。
 *
 * <p>精确模式的日集合唯一真相是 check_ins 表，但编辑页是个暂存区：弹窗里勾的日子
 * 先放在弹窗自己的副本里，点「保存」才进 {@link #pending}，整本书保存时才落库。
 * 这样弹窗点「取消」不会漏写，也不会把没保存的勾选当成历史。
 *
 * <p>落库只写「与打开编辑页时不一样」的轮次：没碰过的轮次一行都不动，
 * 历史打卡（包括模糊轮在打卡页打的那些）原样保留。
 */
final class ReadingDaySets {

    /** 打开编辑页时库里的样子，seq → 升序日键 */
    private final Map<Integer, List<Integer>> saved = new HashMap<>();
    /** 弹窗保存后待落库的样子 */
    private final Map<Integer, List<Integer>> pending = new HashMap<>();
    private boolean loaded;

    /** 一次查询把这本书所有轮次的日子读出来（后台线程 → 回主线程） */
    void load(BookshelfApp app, long bookId, Runnable onDone) {
        app.io(() -> {
            final Map<Integer, List<Integer>> grouped = readGroups(app.db().checkInDao(), bookId);
            app.ui(() -> {
                saved.clear();
                saved.putAll(grouped);
                loaded = true;
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    /** 同步读（调用方自己保证线程）：单测拿内存库直接走这里 */
    void loadFrom(com.bai.bookshelf.data.dao.CheckInDao dao, long bookId) {
        saved.clear();
        saved.putAll(readGroups(dao, bookId));
        loaded = true;
    }

    private static Map<Integer, List<Integer>> readGroups(
            com.bai.bookshelf.data.dao.CheckInDao dao, long bookId) {
        List<CheckIn> rows;
        try {
            rows = dao.getByBook(bookId);
        } catch (Exception e) {
            rows = Collections.emptyList();
        }
        final Map<Integer, List<Integer>> grouped = new HashMap<>();
        for (CheckIn c : rows) {
            List<Integer> keys = grouped.get(c.seq);
            if (keys == null) {
                keys = new ArrayList<>();
                grouped.put(c.seq, keys);
            }
            keys.add(c.dayKey);
        }
        for (List<Integer> keys : grouped.values()) {
            Collections.sort(keys);
        }
        return grouped;
    }

    boolean isLoaded() {
        return loaded;
    }

    /** 某一轮在库里的日子（副本，调用方改不动内部状态） */
    List<Integer> keys(int seq) {
        List<Integer> keys = saved.get(seq);
        return keys == null ? new ArrayList<>() : new ArrayList<>(keys);
    }

    /** 弹窗点保存：把这一轮的待写集合记下来 */
    void commit(int seq, List<Integer> keys) {
        List<Integer> copy = new ArrayList<>(keys);
        Collections.sort(copy);
        pending.put(seq, copy);
    }

    /** 有没有真的改过：pending 里与库里相同的轮次不算脏（弹窗保存时可能原样提交） */
    boolean hasPending() {
        for (Map.Entry<Integer, List<Integer>> e : pending.entrySet()) {
            List<Integer> before = saved.get(e.getKey());
            if (before == null || !before.equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 落库（必须在 io 线程调用）：只写有差异的轮次，先清后插。
     *
     * <p>补录的日子 backfill 记 true（dayKey 早于今天 = 事后补的），
     * 与打卡页「补卡」的语义一致，日历上才会画成空心点。
     */
    void persist(BookshelfApp app, long bookId) {
        persist(app.db().checkInDao(), bookId);
    }

    /** 同步写（调用方自己保证线程）：单测拿内存库直接走这里 */
    void persist(com.bai.bookshelf.data.dao.CheckInDao dao, long bookId) {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int today = DateKeys.todayKey();
        for (Map.Entry<Integer, List<Integer>> e : pending.entrySet()) {
            int seq = e.getKey();
            List<Integer> keys = e.getValue();
            List<Integer> before = saved.get(seq);
            if (before != null && before.equals(keys)) {
                continue;
            }
            dao.deleteByBookSeq(bookId, seq);
            if (!keys.isEmpty()) {
                List<CheckIn> rows = new ArrayList<>(keys.size());
                for (int key : keys) {
                    CheckIn c = new CheckIn();
                    c.bookId = bookId;
                    c.dayKey = key;
                    c.createdAt = now;
                    c.backfill = key < today;
                    c.seq = seq;
                    rows.add(c);
                }
                dao.insertAll(rows);
            }
            saved.put(seq, new ArrayList<>(keys));
        }
        pending.clear();
    }

    /** 供单测/体检看「这一轮现在库里是几天」，不暴露内部 map */
    int savedCount(int seq) {
        return ReadDays.precise(saved.get(seq));
    }
}
