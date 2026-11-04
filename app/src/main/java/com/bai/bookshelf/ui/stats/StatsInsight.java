package com.bai.bookshelf.ui.stats;

import java.util.Locale;

import com.bai.bookshelf.util.Money;

/**
 * 统计页的洞察文案（v5.9.0 从 StatsFragment 抽出）：一行一个结论，都带口径与样本数。
 *
 * <p>这里全是「数据进、文字出」的纯函数，不碰 View、不查库，所以能直接单测；
 * StatsFragment 只负责把结果贴到卡片上。
 */
final class StatsInsight {

    private StatsInsight() {
    }

    static String stocktakeLine(com.bai.bookshelf.util.StatsHoldings.Holdings h) {
        StringBuilder sb = new StringBuilder("盘点：在架 ").append(h.copiesInStore()).append(" 册 · 在外 ")
                .append(h.copiesLent).append(" · 遗失 ").append(h.copiesLost);
        if (h.lossRate() > 0) {
            sb.append("（损耗 ").append(Math.round(h.lossRate() * 100)).append("%）");
        }
        return sb.toString();
    }

    static String lendInsight(com.bai.bookshelf.util.StatsLend.Lend l,
                                      java.util.List<com.bai.bookshelf.util.StatsLend.Lender> lenders,
                                      java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> lends,
                                      com.bai.bookshelf.util.StatsHealth.BorrowOrphans orphans) {
        StringBuilder sb = new StringBuilder(lendLine(l));
        if (orphans != null && !orphans.isEmpty()) {
            sb.append('\n').append(orphans.line());
        }
        if (lenders != null && !lenders.isEmpty()) {
            sb.append('\n').append(lenderLine(lenders.get(0)));
        }
        if (lends != null && !lends.isEmpty()) {
            com.bai.bookshelf.util.StatsHoldings.Group top = lends.get(0);
            sb.append('\n').append("借出最多的是「").append(top.name).append("」（")
                    .append(top.titles).append(" 笔");
            if (top.readDone > 0) {
                sb.append(" · 其中读完 ").append(top.readDone);
            }
            sb.append("）");
        }
        return sb.toString();
    }

    static String habitText(com.bai.bookshelf.util.StatsPace.Habit h,
                                    com.bai.bookshelf.util.StatsPace.Stale s,
                                    com.bai.bookshelf.util.StatsPace.BuyRead b) {
        StringBuilder sb = new StringBuilder("今年读过 ").append(h.daysReadThisYear).append(" 天");
        if (h.longestStreak > 0) {
            sb.append(" · 最长连续 ").append(h.longestStreak).append(" 天");
        }
        if (h.currentStreak > 0) {
            sb.append(" · 当前连着 ").append(h.currentStreak).append(" 天");
        }
        sb.append('\n').append("（按阅读记录的起止区间推算，在读且没填结束日期的书算每天在读）");
        if (h.bestMonth > 0) {
            sb.append('\n').append("最勤的是 ").append(h.bestMonth).append(" 月（")
                    .append(h.bestMonthDays).append(" 天）");
            if (h.bestWeekdayName != null) {
                sb.append(" · 一周里 ").append(h.bestWeekdayName).append(" 读得最多");
            }
        }
        sb.append('\n').append("买读比 ").append(b.ratioText());
        if (b.hoardIndex >= 0) {
            sb.append(" · 囤书指数 ").append(Math.round(b.hoardIndex * 100))
                    .append("%（未读 / 在架）");
        }
        if (s.readingOver90 > 0 || s.boughtUnreadCount > 0) {
            sb.append('\n').append("在读超 90 天 ").append(s.readingOver90)
                    .append(" 种 · 买来一年没读 ").append(s.boughtUnreadCount).append(" 种");
            StringBuilder names = new StringBuilder();
            for (String[] row : s.boughtUnread) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append('《').append(row[0]).append('》').append(row[1]).append(" 天");
            }
            if (names.length() > 0) {
                sb.append("：").append(names);
            }
        }
        return sb.toString();
    }

    static String ratingLine(com.bai.bookshelf.util.StatsHealth.Rating r) {
        StringBuilder sb = new StringBuilder();
        if (r.average > 0) {
            sb.append("平均评分 ")
                    .append(String.format(Locale.getDefault(), "%.1f", r.average))
                    .append("（").append(r.rated).append(" 种已打分）");
            if (r.bestCategory != null) {
                sb.append("，最偏爱「").append(r.bestCategory).append("」");
            }
            sb.append(" · 还有 ").append(Math.round(r.unratedRate() * 100)).append("% 没打分");
        } else {
            sb.append("还没开始打分（").append(r.total).append(" 种都没评分）");
        }
        return sb.toString();
    }

    static String anomalyLine(com.bai.bookshelf.util.StatsHealth.Anomalies a,
                                      java.util.List<String> readingOutNames,
                                      int lendDateUnparsable) {
        // v5.8.33：借阅相关的两项单独算，凑不齐全 0 才算「没发现异常」
        int extra = (readingOutNames == null ? 0 : readingOutNames.size())
                + lendDateUnparsable;
        if (a.total() == 0 && extra == 0) {
            return "数据体检：没发现重复或异常记录";
        }
        if (a.total() == 0) {
            return "数据体检 " + extra + " 处要看看" + lendingAnomalyTail(
                    readingOutNames, lendDateUnparsable);
        }
        return "数据体检 " + a.total() + " 处要看看：重复 ISBN "
                + a.duplicateIsbn.size() + "、同名 " + a.duplicateTitle.size()
                + "、负价 " + a.negativePrice + "、未来日期 "
                + a.futureDate + "、价格离群 " + a.overpriced
                + "、页数异常 " + a.oddPages
                // v5.8.31：旧单会话模型把「弃读→再读→读完」压成一条，时长会跨掉弃读空窗
                + (a.longReadWithDrop == 0 ? ""
                : "、阅读时长超 " + com.bai.bookshelf.util.StatsHealth.LONG_READ_DAYS
                + " 天且中途有弃读 " + a.longReadWithDrop + " 本")
                + lendingAnomalyTail(readingOutNames, lendDateUnparsable);
    }

    static String lendingAnomalyTail(java.util.List<String> readingOutNames,
                                             int lendDateUnparsable) {
        StringBuilder sb = new StringBuilder();
        int n = readingOutNames == null ? 0 : readingOutNames.size();
        if (n > 0) {
            sb.append("、在读但全部册在外 ").append(n).append(" 本（这些天不算阅读时长）");
            StringBuilder names = new StringBuilder();
            for (String name : readingOutNames) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append('《').append(name).append('》');
            }
            sb.append("：").append(names);
        }
        if (lendDateUnparsable > 0) {
            sb.append("、借出/归还日期残缺 ").append(lendDateUnparsable)
                    .append(" 条（借出区间算不出来，这段没扣）");
        }
        return sb.toString();
    }

    static String holdingsLine(com.bai.bookshelf.util.StatsHoldings.Holdings h) {
        StringBuilder sb = new StringBuilder("藏书 ");
        sb.append(com.bai.bookshelf.util.Units.holdings(h.titles, h.copies));
        sb.append("；已读 ").append(h.readDone).append(" 种");
        if (h.readRate() >= 0) {
            sb.append("（").append(Math.round(h.readRate() * 100)).append("%）");
        }
        return sb.toString();
    }

    static String spendLine(com.bai.bookshelf.util.StatsSpend.Spend s) {
        StringBuilder sb = new StringBuilder("累计花费 ").append(Money.compact(s.paid))
                .append("（全部自购，不看购买日期），定价合计 ").append(Money.compact(s.listValue));
        if (s.discount >= 0) {
            sb.append("，平均 ").append(s.discountText()).append("，累计省下 ").append(Money.compact(s.saved));
        } else {
            sb.append("，折扣数据不足（仅 ").append(s.discountSample).append(" 种同时有定价与实付）");
        }
        return sb.toString();
    }

    static String costLine(com.bai.bookshelf.util.StatsSpend.Spend s) {
        StringBuilder sb = new StringBuilder();
        if (s.perPage >= 0 || s.per10kWords >= 0) {
            sb.append("单位成本：");
            if (s.perPage >= 0) {
                sb.append("每页 ").append(Money.compact(s.perPage));
            }
            if (s.perPage >= 0 && s.per10kWords >= 0) {
                sb.append(" · ");
            }
            if (s.per10kWords >= 0) {
                sb.append("每一万字 ").append(Money.compact(s.per10kWords));
            }
            sb.append("（按册，样本 ").append(Math.max(s.pageSample, s.wordSample)).append(" 种）");
        } else {
            sb.append("单位成本数据不足（每页 ").append(s.pageSample)
                    .append(" 种 / 每万字 ").append(s.wordSample).append(" 种）");
        }
        return sb.toString();
    }

    static String speedLine(com.bai.bookshelf.util.StatsPace.Pace p) {
        if (p.medianDays <= 0) {
            return "阅读速度数据不足（没有起止日期都齐全的书）";
        }
        StringBuilder sb = new StringBuilder("阅读速度：中位读完 ")
                .append(Math.round(p.medianDays)).append(" 天");
        if (p.medianPagesPerDay > 0) {
            sb.append("，").append(Math.round(p.medianPagesPerDay)).append(" 页/天");
        }
        sb.append("（样本 ").append(p.daySample).append(" 种）");
        return sb.toString();
    }

    static String debtLine(com.bai.bookshelf.util.StatsPace.Pace p) {
        StringBuilder sb = new StringBuilder("书债：未读 ")
                .append(com.bai.bookshelf.util.Units.holdings(p.unreadTitles, p.unreadCopies));
        sb.append("，约 ").append(p.unreadPages).append(" 页、实付 ").append(Money.compact(p.unreadPaid));
        if (p.monthsToClear >= 0) {
            sb.append("，按当前速度约 ").append(Math.max(1, Math.round(p.monthsToClear))).append(" 个月清完");
        } else if (p.unknownReason != null) {
            sb.append("；清完时间算不出：").append(p.unknownReason);
        }
        return sb.toString();
    }

    static String lendLine(com.bai.bookshelf.util.StatsLend.Lend l) {
        StringBuilder sb = new StringBuilder("借阅：在外 ").append(l.out).append(" 笔");
        if (l.overdue > 0) {
            sb.append("，").append(l.overdue).append(" 笔已超期");
        }
        sb.append("；按时归还率 ")
                .append(l.onTimeRate >= 0 ? Math.round(l.onTimeRate * 100) + "%" : "数据不足")
                .append("（样本 ").append(l.onTimeSample).append(" 笔）");
        return sb.toString();
    }

    static String healthLine(com.bai.bookshelf.util.StatsHealth.Health g) {
        if (g.score < 0) {
            return "书房健康分数据不足";
        }
        StringBuilder sb = new StringBuilder("书房健康分 ").append(g.score).append("/100");
        if (g.weakest != null) {
            sb.append("，最该补的是").append(g.weakest)
                    .append("（").append(g.weakestMissing).append(" 种缺）");
        }
        return sb.toString();
    }

    static String lenderLine(com.bai.bookshelf.util.StatsLend.Lender top) {
        StringBuilder sb = new StringBuilder("借得最多的是 ").append(top.name)
                .append("（").append(top.loans).append(" 笔");
        if (top.outNow > 0) {
            sb.append(" · 还在他手上 ").append(top.outNow).append(" 册");
        }
        if (top.overdue > 0) {
            sb.append(" · ").append(top.overdue).append(" 笔超期");
        }
        sb.append("）");
        return sb.toString();
    }

    static String booksInsight(com.bai.bookshelf.util.StatsHoldings.Holdings h,
                                       com.bai.bookshelf.util.StatsHealth.Health g,
                                       com.bai.bookshelf.util.StatsHealth.Rating r,
                                       com.bai.bookshelf.util.StatsHealth.Anomalies a,
                                       java.util.List<String> readingOutNames,
                                       int lendDateUnparsable) {
        StringBuilder sb = new StringBuilder(holdingsLine(h)).append('\n');
        sb.append(healthLine(g)).append('\n');
        sb.append(ratingLine(r)).append('\n');
        sb.append(anomalyLine(a, readingOutNames, lendDateUnparsable));
        if (h.copiesLost > 0) {
            sb.append('\n').append("损耗：遗失 ").append(h.copiesLost).append(" 册、")
                    .append(Money.compact(h.lostValue));
        }
        sb.append('\n').append(stocktakeLine(h));
        return sb.toString();
    }

    static String readingInsight(com.bai.bookshelf.util.StatsPace.Pace p,
                                         com.bai.bookshelf.util.StatsPace.Backlog backlog,
                                         com.bai.bookshelf.util.StatsHealth.NoteStat n,
                                         com.bai.bookshelf.util.StatsPace.DropRecovery drops) {
        return speedLine(p) + "\n" + debtLine(p) + "\n" + backlog.line()
                + "\n" + drops.line() + "\n" + n.line();
    }

    static String purchaseInsight(com.bai.bookshelf.util.StatsSpend.Spend s,
                                          com.bai.bookshelf.util.StatsSpend.Relation r,
                                          java.util.List<com.bai.bookshelf.util.StatsSpend.PeriodDelta> periods,
                                          com.bai.bookshelf.util.StatsHealth.WishStat w) {
        StringBuilder sb = new StringBuilder(spendLine(s)).append('\n');
        sb.append(costLine(s)).append('\n');
        for (com.bai.bookshelf.util.StatsSpend.PeriodDelta p : periods) {
            sb.append(p.line()).append('\n');
        }
        sb.append(r.line()).append('\n');
        sb.append(w.line());
        return sb.toString();
    }
}
