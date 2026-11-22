package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.ReadDays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 打开编辑页时的原始快照（脏检查基准：面板「确定」是立即落库，必须能被比对出来） */
    private final Map<Integer, List<Integer>> initial = new HashMap<>();
    /** 弹窗保存后待落库的样子 */
    private final Map<Integer, List<Integer>> pending = new HashMap<>();
    /** 删掉的轮次：整本书保存时把对应 check_ins 一并清掉（v5.9.8 删轮级联） */
    private final Set<Integer> forget = new HashSet<>();
    private boolean loaded;

    /** 一次查询把这本书所有轮次的日子读出来（后台线程 → 回主线程） */
    void load(BookshelfApp app, long bookId, Runnable onDone) {
        app.io(() -> {
            final Map<Integer, List<Integer>> grouped = readGroups(app.db().checkInDao(), bookId);
            app.ui(() -> {
                saved.clear();
                saved.putAll(grouped);
                initial.clear();
                initial.putAll(grouped);
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
        initial.clear();
        initial.putAll(saved);
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

    /** 面板「确定」已经直接落库了：把缓存刷新到最新，不进 pending（避免保存时再重写一遍） */
    void noteSaved(int seq, List<Integer> keys) {
        List<Integer> copy = new ArrayList<>(keys);
        Collections.sort(copy);
        saved.put(seq, copy);
    }

    /** 弹窗点保存：把这一轮的待写集合记下来 */
    void commit(int seq, List<Integer> keys) {
        List<Integer> copy = new ArrayList<>(keys);
        Collections.sort(copy);
        pending.put(seq, copy);
    }

    /**
     * 删除某一轮（v5.9.8）：从暂存区撤下并登记待删，随整本书保存一起落库。
     *
     * <p>写路径（commit/persist）一直有级联，删路径此前没有——轮次记录删了，
     * check_ins 里那一轮的行还留着，打卡日历上就成了查无实据的点。登记是
     * 无条件的：记录还没加载完或这一轮本来就没有行时，deleteByBookSeq 是空操作。
     */
    void forgetRound(int seq) {
        pending.remove(seq);
        forget.add(seq);
    }

    /** 「设为未读」清空全部记录：所有已知轮次一并登记待删 */
    void forgetAll() {
        pending.clear();
        forget.addAll(saved.keySet());
    }

    /** 有没有真的改过：pending 里与库里相同的轮次不算脏（弹窗保存时可能原样提交） */
    boolean hasPending() {
        if (!forget.isEmpty()) {
            return true;
        }
        for (Map.Entry<Integer, List<Integer>> e : pending.entrySet()) {
            List<Integer> before = saved.get(e.getKey());
            if (before == null || !before.equals(e.getValue())) {
                return true;
            }
        }
        // v5.12.1：DayPicker 面板「确定」是立即落库（不走 pending），与进页快照
        // 比对才看得见这类改动——否则改完日子直接退出连未保存确认都不弹
        for (Map.Entry<Integer, List<Integer>> e : saved.entrySet()) {
            List<Integer> atEntry = initial.get(e.getKey());
            if (atEntry == null || !atEntry.equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 落库（必须在 io 线程调用）：先落删轮级联，再只写有差异的轮次，先清后插。
     *
     * <p>这里是编辑页专属路径：补录过去的日子是「补记历史」而不是打卡页那种
     * 「事后补卡」，backfill 记 false，日历上画实心点（v5.9.8 语义拆分）；
     * 打卡页 DayPickerSheet 的补卡语义不变，仍走 applyDayKeys 的默认 true。
     */
    void persist(BookshelfApp app, long bookId) {
        persist(app.db().checkInDao(), bookId);
    }

    /** 同步写（调用方自己保证线程）：单测拿内存库直接走这里 */
    void persist(com.bai.bookshelf.data.dao.CheckInDao dao, long bookId) {
        // 删轮级联先落：即使没有任何 pending，也要把登记过的轮次清干净。
        // 不动 pending——forget 登记之后又 commit 的同一 seq 是「删了重建」，
        // 后面的 pending 循环会先清后插，以重建内容为准。
        for (Integer seq : forget) {
            dao.deleteByBookSeq(bookId, seq);
            saved.remove(seq);
        }
        forget.clear();
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
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
                    c.backfill = false; // 编辑页补录 = 补记历史，不算补卡（v5.9.8）
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
