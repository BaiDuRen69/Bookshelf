package com.bai.bookshelf.ui.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.dao.BookSeqStat;
import com.bai.bookshelf.data.dao.CheckInDao;
import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 删轮级联与补卡语义拆分（v5.9.8）的纯 JVM 校验。
 *
 * <p>真库的按轮查询归仪器化测试；这里用内存版 CheckInDao 钉住三件事：
 * 删轮 → persist → 该 seq 在 check_ins 里 0 行且别轮不动；「设为未读」清空全部轮次；
 * 编辑页补录历史 backfill 记 false（不再画空心点、不进补卡计数）。
 */
public class ReadingDaySetsRoundTest {

    /** 内存版 DAO：只实现本测试用到的路径，其余一律拒绝（用到了会立刻炸出来） */
    private static final class FakeDao implements CheckInDao {
        final Map<Long, List<CheckIn>> byBook = new HashMap<>();

        List<CheckIn> book(long bookId) {
            return byBook.computeIfAbsent(bookId, k -> new ArrayList<>());
        }

        void seed(CheckIn c) {
            book(c.bookId).add(c);
        }

        @Override
        public long insert(CheckIn c) {
            book(c.bookId).add(c);
            return 1;
        }

        @Override
        public void delete(CheckIn c) {
            List<CheckIn> rows = book(c.bookId);
            for (int i = 0; i < rows.size(); i++) {
                CheckIn r = rows.get(i);
                if (r.dayKey == c.dayKey) {
                    rows.remove(i);
                    return;
                }
            }
        }

        @Override
        public void deleteByBook(long bookId) {
            byBook.remove(bookId);
        }

        @Override
        public int deleteByBookAndDay(long bookId, int dayKey) {
            List<CheckIn> rows = book(bookId);
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).dayKey == dayKey) {
                    rows.remove(i);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<CheckIn> getInRange(int startKey, int endKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CheckIn> getByDay(int dayKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CheckIn> getByBook(long bookId) {
            return new ArrayList<>(book(bookId));
        }

        @Override
        public int countAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CheckIn> getAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertAll(List<CheckIn> list) {
            for (CheckIn c : list) {
                book(c.bookId).add(c);
            }
        }

        @Override
        public void deleteAll() {
            byBook.clear();
        }

        @Override
        public List<CheckIn> getByBookSeq(long bookId, int seq) {
            List<CheckIn> out = new ArrayList<>();
            for (CheckIn c : book(bookId)) {
                if (c.seq == seq) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public List<Integer> getDayKeys(long bookId, int seq) {
            List<Integer> keys = new ArrayList<>();
            for (CheckIn c : book(bookId)) {
                if (c.seq == seq) {
                    keys.add(c.dayKey);
                }
            }
            return keys;
        }

        @Override
        public int countRoundDays(long bookId, int seq) {
            return getDayKeys(bookId, seq).size();
        }

        @Override
        public List<BookSeqStat> statsForBooks(List<Long> bookIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Integer> distinctDaysUpTo(int todayKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countOnDay(int dayKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateSeq(long bookId, List<Integer> keys, int toSeq) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteByBookSeq(long bookId, int seq) {
            book(bookId).removeIf(c -> c.seq == seq);
        }
    }

    private static CheckIn row(long bookId, int dayKey, int seq, boolean backfill) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.seq = seq;
        c.backfill = backfill;
        return c;
    }

    @Test
    public void 删轮保存后该seq清空且别轮不动() {
        FakeDao dao = new FakeDao();
        dao.seed(row(1, 20261101, 1, false));
        dao.seed(row(1, 20261102, 1, false));
        dao.seed(row(1, 20261110, 2, true));
        dao.seed(row(1, 20261111, 2, true));
        ReadingDaySets sets = new ReadingDaySets();
        sets.loadFrom(dao, 1);
        assertEquals(2, sets.savedCount(1));
        assertEquals(2, sets.savedCount(2));

        sets.forgetRound(2);
        assertTrue("删轮要算改动，否则整本书保存时不会落库", sets.hasPending());
        sets.persist(dao, 1);

        assertEquals("被删的第 2 轮必须 0 行", 0, dao.getByBookSeq(1, 2).size());
        assertEquals("第 1 轮不能被误伤", 2, dao.getByBookSeq(1, 1).size());
        assertFalse("落库后不再算脏", sets.hasPending());
    }

    @Test
    public void 设为未读清空全部轮次() {
        FakeDao dao = new FakeDao();
        dao.seed(row(1, 20261101, 1, false));
        dao.seed(row(1, 20261110, 2, true));
        ReadingDaySets sets = new ReadingDaySets();
        sets.loadFrom(dao, 1);

        sets.forgetAll();
        sets.persist(dao, 1);

        assertEquals("全部轮次的打卡都要清掉", 0, dao.getByBook(1).size());
    }

    @Test
    public void 编辑页补录历史不算补卡() {
        FakeDao dao = new FakeDao();
        ReadingDaySets sets = new ReadingDaySets();
        // 三个过去的日子：编辑页的「补记历史」路径，backfill 必须是 false
        sets.commit(1, Arrays.asList(20261101, 20261102, 20261103));
        sets.persist(dao, 1);

        List<CheckIn> rows = dao.getByBookSeq(1, 1);
        assertEquals(3, rows.size());
        for (CheckIn c : rows) {
            assertFalse("编辑页补录 = 这轮阅读本来的样子，不是补卡", c.backfill);
        }
    }

    @Test
    public void 先删后重建同一seq时保存结果以重建为准() {
        FakeDao dao = new FakeDao();
        dao.seed(row(1, 20261110, 2, true));
        ReadingDaySets sets = new ReadingDaySets();
        sets.loadFrom(dao, 1);

        sets.forgetRound(2);
        sets.commit(2, Arrays.asList(20261112));
        sets.persist(dao, 1);

        List<CheckIn> rows = dao.getByBookSeq(1, 2);
        assertEquals(1, rows.size());
        assertEquals(20261112, rows.get(0).dayKey);
        assertFalse(rows.get(0).backfill);
    }
}
