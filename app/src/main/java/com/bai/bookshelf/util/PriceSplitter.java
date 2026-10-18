package com.bai.bookshelf.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 套装书籍购入价分摊器：
 * 一次购买一套多本书，按每本书定价占总定价的比例，把实际支付总额分摊到每本书。
 *
 * 规则：
 * - 每本书的相对购入价 = totalPaid × (book.price / sumPrice)，保留两位小数；
 * - 为避免四舍五入导致总额对不上，前 n-1 本按公式计算，
 *   最后一本 = totalPaid − 前面所有已分摊金额之和；
 * - 边界：书籍列表为空 / 定价之和为 0 / 实付为负 / 某本定价为负 → 抛 SplitException。
 *
 * 示例：A 定价 10、B 定价 90、实付 10 → A 分摊 1.00，B 分摊 9.00。
 */
public final class PriceSplitter {

    /** 输入：一本书的书名与定价 */
    public static class BookInput {
        public long bookId;
        public String bookName;
        public double price;

        public BookInput(long bookId, String bookName, double price) {
            this.bookId = bookId;
            this.bookName = bookName;
            this.price = price;
        }
    }

    /** 输出：一本书的分摊结果（含原定价、分摊金额、分摊比例） */
    public static class AllocResult {
        public long bookId;
        public String bookName;
        public double price;          // 原定价
        public double allocatedPrice; // 相对购入价（两位小数）
        public double ratio;          // 分摊比例（0~1）

        public AllocResult(long bookId, String bookName, double price,
                           double allocatedPrice, double ratio) {
            this.bookId = bookId;
            this.bookName = bookName;
            this.price = price;
            this.allocatedPrice = allocatedPrice;
            this.ratio = ratio;
        }
    }

    /** 分摊输入不合法时抛出 */
    public static class SplitException extends Exception {
        public SplitException(String message) {
            super(message);
        }
    }

    private PriceSplitter() {
    }

    /**
     * 按定价比例分摊实付总额。
     *
     * @param totalPaid 实际支付总金额（不能为负）
     * @param books     书籍列表（不能为空；单本定价不能为负；定价之和不能为 0）
     * @return 每本书的分摊结果，顺序与输入一致
     */
    public static List<AllocResult> split(double totalPaid, List<BookInput> books) throws SplitException {
        if (books == null || books.isEmpty()) {
            throw new SplitException("书籍列表为空，请先添加书籍");
        }
        if (totalPaid < 0) {
            throw new SplitException("实际支付金额不能为负数");
        }
        double sumPrice = 0;
        for (BookInput b : books) {
            if (b.price < 0) {
                throw new SplitException("「" + b.bookName + "」的定价不能为负数");
            }
            sumPrice += b.price;
        }
        if (sumPrice == 0) {
            throw new SplitException("定价之和为 0，无法按比例分摊");
        }

        List<AllocResult> results = new ArrayList<>();
        double allocatedSum = 0;
        int n = books.size();
        for (int i = 0; i < n; i++) {
            BookInput b = books.get(i);
            double alloc;
            if (i < n - 1) {
                // 前 n-1 本：按公式计算并保留两位小数
                alloc = round2(totalPaid * b.price / sumPrice);
                allocatedSum += alloc;
            } else {
                // 最后一本：兜底差额，保证合计 = 实付总额
                alloc = round2(totalPaid - allocatedSum);
            }
            results.add(new AllocResult(b.bookId, b.bookName, b.price, alloc, b.price / sumPrice));
        }
        return results;
    }

    /** 保留两位小数（四舍五入，用 BigDecimal 避免浮点误差） */
    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
