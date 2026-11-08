package com.bai.bookshelf.ui.checkin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.data.dao.BookSeqStat;
import com.bai.bookshelf.databinding.FragmentCheckinBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.MainActivity;
import com.bai.bookshelf.ui.TabRefresh;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.CheckInCalendarState;
import com.bai.bookshelf.util.CheckInDayDetail;
import com.bai.bookshelf.util.CheckInHeader;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.HolidayCalendar;
import com.bai.bookshelf.util.HolidayStore;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.Motion;
import com.bai.bookshelf.util.ReadingRecords;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打卡页（第 ③ 步）：月视图 + 在读书籍列表 + 打卡/补卡/撤销。
 *
 * <p>所有 DB 读写都走 BookshelfApp.io()，UI 更新回主线程；toggle 本身不碰
 * readStatus 与阅读会话。打卡成功后只重绑受影响的一行与一个日历格，不整表重建。
 */
public class CheckInFragment extends Fragment
        implements MonthGridView.Listener, CheckInSwipeRoot.Listener,
        CheckInAdapter.Listener, TabRefresh {

    private static final String TAG = "CheckInPerf";
    /** 5 秒内可撤销的单日批量打卡；换日/换月后按记录的原日期生效 */
    private static final long BATCH_UNDO_MS = 5000L;
    /** 撤销按钮出现/收起 */
    private static final long DAY_UNDO_MS = 160L;

    private FragmentCheckinBinding b;
    private CheckInAdapter adapter;
    private int selectedDayKey;
    private int todayKey;
    /** 今天是否已有任意一本打卡（第 9 项）：缓存命中时也要先把这个状态贴回网格 */
    private boolean todayChecked;
    private long lastReloadMs;
    private CheckInViewSwitcher switcher;
    private CheckInYearPanel yearPanel;
    private CheckInMonthDayAdapter monthDayAdapter;
    /** 在读清单本身为空：没有任何「在读」的书 */
    private boolean readingEmpty;
    /** 在读清单不为空，但选中日的行全部早于开读日而被过滤光 */
    private boolean dayEmpty;
    /** 年→月转场期间把列表/小结/图例淡掉，数据回来后再显现 */
    private boolean monthChromeHidden;
    /** 「bookId:dayKey」在途请求：同一格连点、换日回来再点都不会重复写库 */
    private final Set<String> pendingOps = new HashSet<>();
    /** 系统改日期/时间/时区后重取今天；分钟级没有轮询，跨天另外用 Handler */
    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onClockChanged();
        }
    };
    private final Handler midnightHandler = new Handler(Looper.getMainLooper());
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable midnightTask = this::onClockChanged;
    private boolean clockReceiverRegistered;
    private boolean batchPending;
    private long batchUndoGeneration;
    private List<Long> batchUndoIds = new ArrayList<>();
    private int batchUndoDayKey;
    /** 当前可见的日动作条文案；两行 TextView 交叉淡入时用它做去重 */
    private String dayActionText = "";
    @Nullable
    private ValueAnimator dayActionAnim;
    /** 当月书名表（含非在读与已删书的缺失占位），当天记录明细拿它显示书名 */
    private Map<Long, String> monthBookTitles = new HashMap<>();
    /** 相邻月快照：切月时先把打卡点铺上，列表/小结再由 loadMonth 补齐（第 8 项） */
    private final LruCache<Integer, MonthSnapshot> monthSnapshots = new LruCache<>(4);

    // 三态（本轮只接 MONTH↔WEEK；DETAIL 等逐日列表规格）
    private CheckInCalendarState.Mode mode = CheckInCalendarState.Mode.MONTH;
    private int toolbarFullPx;
    private int toolbarCompactPx;
    private float titleFullPx;
    private float titleCompactPx;
    private float relFullPx;
    private float relCompactPx;
    /** 当前已套用的基准高度档（false = 常规 / true = 紧凑），避免每帧 setBaseHeight */
    private boolean headerCompactBase;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentCheckinBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        todayKey = DateKeys.todayKey();
        selectedDayKey = todayKey;
        mode = CheckInCalendarState.Mode.MONTH; // 新视图从整月开始
        headerCompactBase = false;

        View yearContentView = b.getRoot().findViewById(R.id.year_content);
        yearPanel = new CheckInYearPanel(yearContentView);
        yearPanel.setListener(new CheckInYearPanel.Listener() {
            @Override
            public void onPick(int yearMonth, int dayKey) {
                exitYearToMonth(yearMonth, dayKey);
            }

            @Override
            public void onYearChanged(int year) {
                // 年份标题与今年小结由 CheckInYearPanel 内部刷新；节日表按新年份补一次
                refreshHolidaysForYear(year);
            }
        });
        switcher = new CheckInViewSwitcher(b.viewSwitchHost, b.monthContent,
                yearContentView, yearPanel.yearView(), b.monthGrid);
        yearPanel.yearView().setYearSwitcher(switcher);

        // 纵向手势的唯一入口：整页可滑，列表/悬浮按钮/年视图分别让行
        b.swipeRoot.setListener(this);
        b.swipeRoot.setGuard(() -> switcher == null
                || switcher.isYearVisible() || switcher.isAnimating());
        b.swipeRoot.setScrollList(b.rvCheckin);
        b.swipeRoot.setExcludedList(b.rvMonthDays);
        b.swipeRoot.setExcludedView(b.fabToday);

        InsetsHelper.applyTopInset(b.toolbar);
        // v5.9.2：悬浮「今」与两个列表的底部避让要加上导航栏 inset，
        // 否则屏幕内导航（手势条/三键）机型上会被系统栏压住一截
        InsetsHelper.applyDockReserveMargin(b.fabToday);
        InsetsHelper.applyDockReservePadding(b.rvCheckin);
        InsetsHelper.applyDockReservePadding(b.rvMonthDays);
        toolbarFullPx = getResources().getDimensionPixelSize(R.dimen.toolbar_h);
        toolbarCompactPx = getResources().getDimensionPixelSize(R.dimen.toolbar_h_compact);
        titleFullPx = b.tvMonthTitle.getTextSize();
        titleCompactPx = getResources().getDimension(R.dimen.text_body);
        relFullPx = b.tvMonthRelative.getTextSize();
        relCompactPx = getResources().getDimension(R.dimen.text_caption_compact);
        // 页头与月历折叠共用同一个 220ms 进度：每帧由网格回调，不另起动画
        b.monthGrid.setCollapseProgressListener(this::applyHeaderProgress);
        setToolbarMonth(b.monthGrid.getYearMonth());
        b.toolbar.setOnClickListener(v -> toggleYearView());
        b.fabToday.setOnClickListener(v -> {
            if (switcher.isAnimating()) {
                return;
            }
            if (switcher.isYearVisible()) {
                final int[] todayPick = yearPanel.todayPick(DateKeys.todayKey());
                // 「今」返回时缩放中心落在今天格，不是上一次选中的日子
                b.monthGrid.setSelectedDay(todayPick[1]);
                switcher.toMonth(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost),
                        this::goToToday);
            } else {
                goToToday();
            }
        });
        // 日动作条：同月回到今天必须重取列表，否则只是网格换了选中圈
        b.btnBackToday.setOnClickListener(v -> goToToday());
        b.btnBatchCheckin.setOnClickListener(v -> batchCheckIn());
        b.btnDayUndo.setOnClickListener(v -> undoBatchCheckIn());

        adapter = new CheckInAdapter();
        adapter.setListener(this);
        b.rvCheckin.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvCheckin.setAdapter(adapter);

        monthDayAdapter = new CheckInMonthDayAdapter();
        monthDayAdapter.setListener(new CheckInMonthDayAdapter.Listener() {
            @Override
            public void onDayClick(CheckInDayDetail.Day day) {
                onMonthDayClick(day);
            }

            @Override
            public void onDayRecords(CheckInDayDetail.Day day) {
                onMonthDayRecords(day);
            }
        });
        b.rvMonthDays.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvMonthDays.setAdapter(monthDayAdapter);
        b.llMonthDaysHandle.setOnClickListener(v -> toggleMonthDays());

        // 空态两套文案：「还没有在读的书」与「这一天还没有可打卡的书」共用同一个 include
        renderEmptyState();
        b.emptyState.btnEmptyCta.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).showTab(MainActivity.TAB_SHELF);
            }
        });

        b.monthGrid.setListener(this);
        renderLegend();
        applyMode(mode, false); // 初始档位：整月 + 书目列表
        lastReloadMs = android.os.SystemClock.elapsedRealtime();
        BookshelfApp.get().whenDbReady(() -> loadMonth(b.monthGrid.getYearMonth()));
    }

    /**
     * 「今」与日动作条「回到今天」共用。同月时 moveToToday 不回调 onMonthChanged，
     * 只换网格选中圈，列表/日动作条会停在旧日期——这里补一次重取。
     */
    private void goToToday() {
        if (b == null || switcher == null || switcher.isAnimating()) {
            return;
        }
        final int before = b.monthGrid.getYearMonth();
        b.monthGrid.moveToToday();
        todayKey = DateKeys.todayKey();
        selectedDayKey = b.monthGrid.getSelectedDayKey();
        if (b.monthGrid.getYearMonth() == before) {
            setToolbarMonth(before);
            loadMonth(before);
        }
        // 换月时 setYearMonth -> onMonthChanged 已经取过数，不重复
    }

    // ==================== 跨天 / 系统时间变化 ====================

    /** 日期、时间或时区被改后重算「今天」；隐藏分页只更新网格，列表等切回来再取 */
    private void onClockChanged() {
        final int prev = todayKey;
        todayKey = DateKeys.todayKey();
        if (b != null && isAdded()) {
            b.monthGrid.refreshTodayKey();
            if (prev != todayKey) {
                setToolbarMonth(b.monthGrid.getYearMonth());
                if (!isHidden()) {
                    loadMonth(b.monthGrid.getYearMonth());
                }
                // 跨 0 点后小组件的「今日已打卡」要归零
                com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(requireContext());
            }
        }
        scheduleMidnightRefresh();
    }

    /** 没有分钟级轮询：只在下一次本地零点后 1 秒补一刀，跨天时列表与日动作条自己更新 */
    private void scheduleMidnightRefresh() {
        midnightHandler.removeCallbacks(midnightTask);
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar next = (java.util.Calendar) now.clone();
        next.add(java.util.Calendar.DAY_OF_YEAR, 1);
        next.set(java.util.Calendar.HOUR_OF_DAY, 0);
        next.set(java.util.Calendar.MINUTE, 0);
        next.set(java.util.Calendar.SECOND, 1);
        next.set(java.util.Calendar.MILLISECOND, 0);
        long delay = Math.max(1000L, next.getTimeInMillis() - now.getTimeInMillis());
        midnightHandler.postDelayed(midnightTask, delay);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!clockReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            ContextCompat.registerReceiver(requireContext(), clockReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            clockReceiverRegistered = true;
        }
        scheduleMidnightRefresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (clockReceiverRegistered) {
            requireContext().unregisterReceiver(clockReceiver);
            clockReceiverRegistered = false;
        }
        midnightHandler.removeCallbacks(midnightTask);
    }

    /** 标题栏在年视图里就是返回月视图的入口 */
    private void toggleYearView() {
        if (switcher == null || switcher.isAnimating()) {
            return;
        }
        if (switcher.isYearVisible()) {
            switcher.toMonth(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost), null);
        } else {
            openYearView();
        }
    }

    /**
     * 年视图同页内切换：先异步取数（动画前完成，避免边动边查库掉帧），
     * 再以当前选中日期格为缩放中心放大进入。
     */
    private void openYearView() {
        if (b == null || switcher == null || switcher.isAnimating()
                || switcher.isYearVisible()) {
            return;
        }
        // 进年视图前先展开整月（不带动画），缩放中心按展开后的格子算
        applyMode(CheckInCalendarState.Mode.MONTH, false);
        final int ym = b.monthGrid.getYearMonth();
        BookshelfApp app = BookshelfApp.get();
        final int year = CheckInMonthGrid.yearOf(ym);
        app.io(() -> {
            List<CheckIn> all;
            try {
                all = app.db().checkInDao().getInRange(0, 99999999);
            } catch (Exception ignored) {
                all = new ArrayList<>();
            }
            final List<CheckIn> list = all;
            // 先把该年节日表放进内存，年视图 bind 时的 HolidaySource 就能同步命中
            HolidayStore.load(app, year);
            final int today = DateKeys.todayKey();
            app.ui(() -> {
                if (b == null || !isAdded()) {
                    return;
                }
                // 年份边界、网格 bind、标题与小结全部收进 CheckInYearPanel
                yearPanel.bind(ym, list, today);
                switcher.toYear(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost), null);
                refreshHolidaysForYear(year);
            });
        });
    }

    /**
     * 年视图点某月：一次转场连续缩小回月视图，不再先放大卡片再切月。
     *
     * <p>转场开始前先用 setYearMonthSilently 把目标月铺好（不播报、不回调），
     * 动画期间看到的就是新月份；列表/小结/图例先淡出，数据回来后由 loadMonth 恢复。
     */
    private void exitYearToMonth(int yearMonth, int dayKey) {
        if (b == null || switcher == null || switcher.isAnimating()) {
            return;
        }
        final int month = CheckInMonthGrid.monthOf(yearMonth);
        final int day = dayKey > 0
                ? dayKey : CheckInMonthGrid.clampDayKey(selectedDayKey, yearMonth);
        selectedDayKey = day;
        applyMode(CheckInCalendarState.Mode.MONTH, false);
        b.monthGrid.setYearMonthSilently(yearMonth, day);
        setToolbarMonth(yearMonth);
        setMonthChromeAlpha(0f);
        monthChromeHidden = true;
        switcher.toMonthFromCard(month, () -> {
            if (b == null) {
                return;
            }
            loadMonth(yearMonth);
        });
    }

    /**
     * 空态：分「还没有在读的书」与「这一天还没有可打卡的书」两套文案。
     * 后者只是翻到了开读日之前，没有可做的事，所以不给 CTA。
     */
    private void renderEmptyState() {
        if (b == null) {
            return;
        }
        if (dayEmpty) {
            b.emptyState.tvEmptyTitle.setText("这一天还没有可打卡的书");
            b.emptyState.tvEmptyDesc.setText("换一天看看");
            b.emptyState.btnEmptyCta.setVisibility(View.GONE);
        } else {
            b.emptyState.tvEmptyTitle.setText("还没有在读的书");
            b.emptyState.tvEmptyDesc.setText("把一本书标成在读，就能每天打卡");
            b.emptyState.btnEmptyCta.setText("打开书架");
            b.emptyState.btnEmptyCta.setVisibility(View.VISIBLE);
        }
    }

    /** 两种空态对外只是一个「列表没有行」的结果，可见性判断统一走这里 */
    private boolean listEmpty() {
        return readingEmpty || dayEmpty;
    }

    /** 年→月转场期间先藏起来的月视图装饰：摘要/图例/列表/空态 */
    private void setMonthChromeAlpha(float alpha) {
        if (b == null) {
            return;
        }
        b.llCheckinSummary.setAlpha(alpha);
        b.llDayActions.setAlpha(alpha);
        b.llCheckinLegend.setAlpha(alpha);
        b.tvLegendNote.setAlpha(alpha);
        b.rvCheckin.setAlpha(alpha);
        b.emptyState.getRoot().setAlpha(alpha);
    }

    /** 月视图列表与空态的可见性：DETAIL 显示逐日明细，其余按两种空态决定 */
    private void updateMonthListVisibility() {
        if (b == null) {
            return;
        }
        boolean detail = mode == CheckInCalendarState.Mode.DETAIL;
        boolean empty = listEmpty();
        b.rvCheckin.setVisibility(!detail && !empty ? View.VISIBLE : View.GONE);
        b.emptyState.getRoot().setVisibility(!detail && empty ? View.VISIBLE : View.GONE);
        b.rvMonthDays.setVisibility(detail ? View.VISIBLE : View.GONE);
        renderDayActions();
    }

    /**
     * 选中日动作条：今天/过去/未来的说明 + 未打数 + 回到今天 + 全部打卡/补卡。
     * 全部走纯文案 {@link CheckInUiState}，没有行（空态）或逐日明细时不占位置。
     */
    private void renderDayActions() {
        if (b == null || adapter == null) {
            return;
        }
        boolean show = mode != CheckInCalendarState.Mode.DETAIL && adapter.getItemCount() > 0;
        b.llDayActions.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        int unchecked = 0;
        for (CheckInAdapter.Row row : adapter.rows()) {
            if (!row.checked) {
                unchecked++;
            }
        }
        setDayActionText(CheckInUiState.dayActionNote(
                selectedDayKey, todayKey, unchecked, adapter.getItemCount()));
        b.btnBackToday.setVisibility(selectedDayKey == todayKey ? View.GONE : View.VISIBLE);
        String batch = CheckInUiState.dayActionButton(selectedDayKey, todayKey);
        boolean canBatch = batch != null && unchecked > 0;
        b.btnBatchCheckin.setVisibility(canBatch ? View.VISIBLE : View.GONE);
        if (batch != null) {
            b.btnBatchCheckin.setText(batchPending ? "打卡中…" : batch);
        }
        b.btnBatchCheckin.setEnabled(!batchPending);
    }

    /** 文案变化时 120ms 交叉淡入，避免整行宽度跳动；两行布局叠在同一个 FrameLayout 里 */
    private void setDayActionText(CharSequence text) {
        if (b == null) {
            return;
        }
        String next = text == null ? "" : text.toString();
        if (next.equals(dayActionText)) {
            return;
        }
        TextView visible = b.tvDayAction.getAlpha() >= b.tvDayActionAlt.getAlpha()
                ? b.tvDayAction : b.tvDayActionAlt;
        TextView hidden = visible == b.tvDayAction ? b.tvDayActionAlt : b.tvDayAction;
        String previous = dayActionText;
        dayActionText = next;
        if (dayActionAnim != null) {
            dayActionAnim.cancel();
            dayActionAnim = null;
        }
        if (previous.isEmpty() || !Motion.enabled(requireContext())) {
            visible.setText(next);
            visible.setAlpha(1f);
            hidden.setText(null);
            hidden.setAlpha(0f);
            return;
        }
        hidden.setText(next);
        hidden.setAlpha(0f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Motion.FAST);
        anim.setInterpolator(Motion.STANDARD);
        anim.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            hidden.setAlpha(p);
            visible.setAlpha(1f - p);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (dayActionAnim != a) {
                    return;
                }
                dayActionAnim = null;
                visible.setText(null);
                visible.setAlpha(0f);
                hidden.setAlpha(1f);
            }
        });
        dayActionAnim = anim;
        anim.start();
    }

    /** 撤销按钮出现：右侧 8dp 滑入 + 淡入，行高不变 */
    private void showDayUndo() {
        if (b == null) {
            return;
        }
        View v = b.btnDayUndo;
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!Motion.enabled(requireContext())) {
            v.setAlpha(1f);
            v.setTranslationX(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationX(undoShiftPx());
        v.animate().alpha(1f).translationX(0f)
                .setDuration(DAY_UNDO_MS).setInterpolator(Motion.STANDARD).start();
    }

    /** 撤销按钮收起：淡出 + 向右让出 8dp；动画结束才 GONE，避免布局跳动 */
    private void hideDayUndo() {
        if (b == null) {
            return;
        }
        View v = b.btnDayUndo;
        v.animate().cancel();
        if (!Motion.enabled(requireContext())) {
            v.setAlpha(1f);
            v.setTranslationX(0f);
            v.setVisibility(View.GONE);
            return;
        }
        v.animate().alpha(0f).translationX(undoShiftPx())
                .setDuration(DAY_UNDO_MS).setInterpolator(Motion.STANDARD)
                .withEndAction(() -> {
                    if (b == null) {
                        return;
                    }
                    b.btnDayUndo.setVisibility(View.GONE);
                    b.btnDayUndo.setAlpha(1f);
                    b.btnDayUndo.setTranslationX(0f);
                }).start();
    }

    private float undoShiftPx() {
        return 8 * getResources().getDisplayMetrics().density;
    }

    private void restoreMonthChrome() {
        if (!monthChromeHidden) {
            return;
        }
        monthChromeHidden = false;
        setMonthChromeAlpha(1f);
    }

    /** 页头：今年只说「11月」，其他年份「2025 / 7」+ 右下角小字相对时间 */
    private void setToolbarMonth(int yearMonth) {
        b.tvMonthTitle.setText(CheckInHeader.title(yearMonth, todayKey));
        String rel = CheckInHeader.relative(yearMonth, todayKey);
        b.tvMonthRelative.setText(rel);
        b.tvMonthRelative.setVisibility(rel == null ? View.GONE : View.VISIBLE);
        b.toolbar.setContentDescription(
                b.tvMonthTitle.getText() + (rel == null ? "" : "，" + rel) + "，打开年视图");
    }

    /**
     * 5.3：三态切换。MONTH ↔ WEEK 由月历折叠动画驱动头部；DETAIL 额外把
     * 书目列表换成逐日明细（同一容器里两套列表互斥）。
     */
    private void applyMode(CheckInCalendarState.Mode next, boolean animate) {
        if (b == null) {
            return;
        }
        mode = next;
        boolean detail = next == CheckInCalendarState.Mode.DETAIL;
        b.monthGrid.setWeekMode(next != CheckInCalendarState.Mode.MONTH, animate);
        applyCompactHeader(next != CheckInCalendarState.Mode.MONTH, animate);
        updateMonthListVisibility();
        b.ivMonthDaysArrow.setRotation(detail ? 180f : 0f);
        b.llMonthDaysHandle.setContentDescription(detail
                ? "收起逐日明细，回到书目" : "查看本月逐日明细");
    }

    /** 把手是手势之外的第二入口：MONTH/WEEK ↔ DETAIL */
    private void toggleMonthDays() {
        if (b == null || switcher == null || switcher.isAnimating()
                || switcher.isYearVisible()) {
            return;
        }
        if (mode == CheckInCalendarState.Mode.DETAIL) {
            applyMode(CheckInCalendarState.Mode.MONTH, true);
        } else {
            applyMode(CheckInCalendarState.Mode.DETAIL, true);
            loadMonth(b.monthGrid.getYearMonth());
        }
    }

    /** 点逐日明细某天：选中该日、回到整月，并刷新当天的书单 */
    private void onMonthDayClick(CheckInDayDetail.Day day) {
        if (b == null || day == null || switcher == null || switcher.isAnimating()) {
            return;
        }
        selectedDayKey = day.dayKey;
        b.monthGrid.setSelectedDay(day.dayKey);
        applyMode(CheckInCalendarState.Mode.MONTH, true);
        loadMonth(b.monthGrid.getYearMonth());
    }

    /**
     * 逐日行尾部「⋯」：打开当天记录明细。书目列表只列在读的书，
     * 改读状态/删书之后那天的打卡只能从这里撤。
     */
    private void onMonthDayRecords(CheckInDayDetail.Day day) {
        if (b == null || day == null || !isAdded() || !(requireActivity() instanceof BaseActivity)) {
            return;
        }
        DayRecordsSheet.show((BaseActivity) requireActivity(), day.dayKey,
                new HashMap<>(monthBookTitles), () -> {
                    if (b != null) {
                        invalidateMonthSnapshot(b.monthGrid.getYearMonth());
                        loadMonth(b.monthGrid.getYearMonth());
                    }
                });
    }

    /**
     * 5.4：页头在两档高度间收起。真正的逐帧进度来自网格的同一条 220ms 动画，
     * 这里只负责把最终基准高度写回 InsetsHelper（动画中不 requestApplyInsets）。
     */
    private void applyCompactHeader(boolean compact, boolean animate) {
        if (!animate) {
            applyHeaderProgress(compact ? 1f : 0f);
        }
    }

    /** p = 0 整月 / 1 单周；Toolbar 48↔40dp、标题 17↔15sp、相对时间 11↔10sp、图例淡出 */
    private void applyHeaderProgress(float p) {
        if (b == null) {
            return;
        }
        float t = Math.max(0f, Math.min(1f, p));
        b.tvMonthTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                titleFullPx + (titleCompactPx - titleFullPx) * t);
        b.tvMonthRelative.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                relFullPx + (relCompactPx - relFullPx) * t);
        int contentH = Math.round(toolbarFullPx + (toolbarCompactPx - toolbarFullPx) * t);
        int topInset = InsetsHelper.topInset(b.toolbar);
        ViewGroup.LayoutParams lp = b.toolbar.getLayoutParams();
        if (lp != null && lp.height != contentH + topInset) {
            lp.height = contentH + topInset;
            b.toolbar.setLayoutParams(lp);
        }
        float legendAlpha = 1f - t;
        b.llCheckinLegend.setAlpha(legendAlpha);
        b.tvLegendNote.setAlpha(legendAlpha);
        int legendVis = t >= 0.999f ? View.GONE : View.VISIBLE;
        if (b.llCheckinLegend.getVisibility() != legendVis) {
            b.llCheckinLegend.setVisibility(legendVis);
        }
        if (b.tvLegendNote.getVisibility() != legendVis) {
            b.tvLegendNote.setVisibility(legendVis);
        }
        // 动画的起点/终点各写一次基准，让 inset 监听器以后按新高度重算
        boolean compact = t >= 0.999f;
        if ((t <= 0.001f || compact) && compact != headerCompactBase) {
            headerCompactBase = compact;
            InsetsHelper.setBaseHeight(b.toolbar, compact ? toolbarCompactPx : toolbarFullPx);
        }
    }

    /**
     * 摘要行三单元（本月打卡 / 连续 / 补卡）：数字与单位走 StatsText.metric 的
     * span 方案，不再用两段 TextView 对基线；整句 summaryLine 留给读屏。
     */
    private void renderSummary(int[] summary, int[] streak, boolean todayChecked,
                               int roundDays) {
        int ym = b.monthGrid.getYearMonth();
        String note = CheckInUiState.summaryNote(ym, todayKey, todayChecked, roundDays);
        b.llCheckinSummary.setContentDescription(
                CheckInUiState.summaryLine(ym, summary, streak)
                        + (note == null ? "" : " · " + note));
        CheckInSummaryRow.fill(b.llCheckinSummary,
                CheckInUiState.summaryUnits(summary, streak, note));
    }

    /** 只用那条已有的按日查询数出 {本数, 补卡本数}，不新增 DAO */
    private static int[] dayMark(List<CheckIn> dayList, int dayKey,
                                 Map<Long, Integer> preciseSeq) {
        int[] mark = com.bai.bookshelf.util.CheckInDots.marks(dayList, preciseSeq).get(dayKey);
        return mark == null ? new int[]{0, 0} : mark;
    }

    /** 当天有记录的每本书 → 当前轮 seq（只收精确轮；模糊轮不进表 = 全量聚合） */
    private static Map<Long, Integer> preciseSeqOf(BookshelfApp app, List<CheckIn> rows) {
        Map<Long, Integer> out = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (CheckIn c : rows) {
            ids.add(c.bookId);
        }
        for (long id : ids) {
            Book book = app.db().bookDao().getById(id);
            ReadingRecords.Record current = currentRecord(book);
            if (current != null && current.mode == ReadingRecords.MODE_PRECISE) {
                out.put(id, Math.max(1, current.seq));
            }
        }
        return out;
    }

    /** 当前该记进哪一轮：进行中那场优先，没有就取最后一场 */
    private static ReadingRecords.Record currentRecord(Book book) {
        if (book == null) {
            return null;
        }
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        if (records.isEmpty()) {
            return null;
        }
        ReadingRecords.Record ongoing = ReadingRecords.ongoingReading(records);
        return ongoing != null ? ongoing : ReadingRecords.lastOf(records);
    }

    /**
     * 选中日落在精确轮的日集合里时，这一轮已记几天（v5.9.1 第六步）。
     * 多本书同一天都命中时不猜，返回 0 = 摘要不追加。
     *
     * <p>命中判定只看当天那几行，天数直接用 SQL 聚合结果，不再全表扫。
     */
    private static int selectedRoundDays(List<Book> reading, List<CheckIn> dayRows,
                                         List<BookSeqStat> stats) {
        if (dayRows == null || dayRows.isEmpty()) {
            return 0;
        }
        int hits = 0;
        int days = 0;
        for (Book book : reading) {
            ReadingRecords.Record current = currentRecord(book);
            if (current == null || current.mode != ReadingRecords.MODE_PRECISE) {
                continue;
            }
            int seq = Math.max(1, current.seq);
            boolean hit = false;
            for (CheckIn c : dayRows) {
                if (c.bookId == book.id && c.seq == seq) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            hits++;
            days = dayCount(stats, book.id, seq);
        }
        return hits == 1 ? days : 0;
    }

    /** 从聚合结果里取某本书某一轮的天数；没有行 = 0 */
    private static int dayCount(List<BookSeqStat> stats, long bookId, int seq) {
        if (stats == null) {
            return 0;
        }
        for (BookSeqStat s : stats) {
            if (s.bookId == bookId && s.seq == seq) {
                return s.dayCount;
            }
        }
        return 0;
    }

    /**
     * 一次 getAllOpen 取代每本书各查一次。旧入口 findOpenListByBook 按 copyIndex
     * 升序取第一条，这里等价地保留每本书 copyIndex 最小的那位借阅人。
     */
    private static Map<Long, String> borrowerByBook(List<BorrowRecord> open) {
        Map<Long, String> out = new HashMap<>();
        Map<Long, Integer> copyOf = new HashMap<>();
        if (open == null) {
            return out;
        }
        for (BorrowRecord r : open) {
            Integer old = copyOf.get(r.bookId);
            if (old == null || r.copyIndex < old) {
                copyOf.put(r.bookId, r.copyIndex);
                out.put(r.bookId, r.borrower);
            }
        }
        return out;
    }

    // ==================== 取数 ====================

    /** 一个月的日历快照：切月动画开始前就能把点与节日铺上去 */
    private static final class MonthSnapshot {
        final Map<Integer, int[]> dayMarks;
        final HolidayCalendar holidays;

        MonthSnapshot(Map<Integer, int[]> dayMarks, HolidayCalendar holidays) {
            this.dayMarks = dayMarks;
            this.holidays = holidays;
        }
    }

    /** 当前月加载完就把前后两个月排进后台；命中缓存时不再重复查 */
    private void prefetchAdjacentMonths(int yearMonth) {
        prefetchMonth(CheckInMonthGrid.shiftMonth(yearMonth, -1));
        prefetchMonth(CheckInMonthGrid.shiftMonth(yearMonth, 1));
    }

    private void prefetchMonth(int yearMonth) {
        if (monthSnapshots.get(yearMonth) != null) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        if (app == null) {
            return;
        }
        final int y = CheckInMonthGrid.yearOf(yearMonth);
        final int m = CheckInMonthGrid.monthOf(yearMonth);
        final int start = DateKeys.keyOf(y, m, 1);
        final int end = DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m));
        app.io(() -> {
            final Map<Integer, int[]> marks;
            final HolidayCalendar cal;
            try {
                List<CheckIn> rows = app.db().checkInDao().getInRange(start, end);
                marks = com.bai.bookshelf.util.CheckInDots.marks(rows, preciseSeqOf(app, rows));
                cal = HolidayStore.load(app, y);
            } catch (Exception ignored) {
                return;
            }
            app.ui(() -> monthSnapshots.put(yearMonth, new MonthSnapshot(marks, cal)));
        });
    }

    /** 某个月的数据被改过（打卡/撤销/当天明细）：快照作废，等下次加载重算 */
    private void invalidateMonthSnapshot(int yearMonth) {
        monthSnapshots.remove(yearMonth);
    }

    private void loadMonth(int yearMonth) {
        if (b == null || !isAdded()) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        final int y = CheckInMonthGrid.yearOf(yearMonth);
        final int m = CheckInMonthGrid.monthOf(yearMonth);
        final int start = DateKeys.keyOf(y, m, 1);
        final int end = DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m));
        final int day = selectedDayKey;
        app.io(() -> {
            final long dbStart = BuildConfig.DEBUG ? SystemClock.elapsedRealtime() : 0L;
            final List<CheckIn> monthList;
            final List<CheckIn> dayRows;
            final List<CheckInAdapter.Row> rows = new ArrayList<>();
            final Map<Long, Integer> preciseSeq = new HashMap<>();
            final List<CheckInDayDetail.Day> monthDays;
            final int[] summary;
            final int[] streak;
            final int roundDays;
            final HolidayCalendar holidays;
            final boolean anyReading;
            final boolean todayChecked;
            final int bookCount;
            // 逐日明细的书名取全部藏书（不只在读），删掉的书才能落成「（已删除）」
            final Map<Long, String> bookTitles = new HashMap<>();
            try {
                monthList = app.db().checkInDao().getInRange(start, end);
                dayRows = app.db().checkInDao().getByDay(day);
                Set<Long> marked = new HashSet<>();
                for (CheckIn c : monthList) {
                    marked.add(c.bookId);
                }
                Set<Long> checked = new HashSet<>();
                for (CheckIn c : dayRows) {
                    checked.add(c.bookId);
                }
                List<Book> reading = new ArrayList<>();
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    bookTitles.put(book.id, book.title);
                    if (book.readStatus == Book.READING) {
                        reading.add(book);
                    }
                    if (!marked.contains(book.id)) {
                        continue;
                    }
                    ReadingRecords.Record current = currentRecord(book);
                    if (current != null && current.mode == ReadingRecords.MODE_PRECISE) {
                        preciseSeq.put(book.id, Math.max(1, current.seq));
                    }
                }
                // 第 11 项：未打卡的排前面（同组仍按最近更新）；只在组装时排，
                // 打卡/撤销不重排，行不会在手指下跳
                reading.sort((x, z) -> {
                    boolean checkedX = checked.contains(x.id);
                    boolean checkedZ = checked.contains(z.id);
                    if (checkedX != checkedZ) {
                        return checkedX ? 1 : -1;
                    }
                    return Long.compare(z.updatedAt, x.updatedAt);
                });
                anyReading = !reading.isEmpty();
                // 只按在读书聚合天数：不再把整张 check_ins 读出来逐行数
                List<Long> readingIds = new ArrayList<>();
                for (Book book : reading) {
                    readingIds.add(book.id);
                }
                final List<BookSeqStat> stats = readingIds.isEmpty()
                        ? new ArrayList<>() : app.db().checkInDao().statsForBooks(readingIds);
                final Map<Long, String> borrowers =
                        borrowerByBook(app.db().borrowRecordDao().getAllOpen());
                for (Book book : reading) {
                    CheckInAdapter.Row row = new CheckInAdapter.Row(
                            book, checked.contains(book.id));
                    String borrower = borrowers.get(book.id);
                    if (borrower != null) {
                        row.borrower = borrower;
                    }
                    ReadingRecords.Record current = currentRecord(book);
                    int recorded = 0;
                    if (current != null) {
                        row.seq = Math.max(1, current.seq);
                        recorded = dayCount(stats, book.id, row.seq);
                        // 精确轮的天数就是聚合出的日集合大小；模糊轮仍按区间算
                        row.readDays = current.mode == ReadingRecords.MODE_PRECISE
                                ? recorded : ReadingRecords.readDays(current, null, null);
                    }
                    // 选中日早于开读日：该书当天整行不出现；已打卡的历史行仍保留，允许撤销
                    if (CheckInUiState.hiddenBeforeStart(row.checked, day,
                            CheckInStore.earliestCheckInKey(book, row.seq, recorded))) {
                        continue;
                    }
                    rows.add(row);
                }
                summary = CheckInStore.monthSummary(monthList, y, m);
                streak = CheckInStore.streakDaysOf(
                        app.db().checkInDao().distinctDaysUpTo(todayKey), todayKey);
                roundDays = selectedRoundDays(reading, dayRows, stats);
                todayChecked = app.db().checkInDao().countOnDay(todayKey) > 0;
                holidays = HolidayStore.load(app, y);
                monthDays = CheckInDayDetail.ofMonth(monthList, bookTitles, y * 100 + m, todayKey);
                bookCount = bookTitles.size();
            } catch (Exception ignored) {
                app.ui(this::restoreMonthChrome); // 别让转场藏起来的 chrome 永远停在 0
                return; // 库未就绪 / 修复中，等下一次回调
            }
            final Map<Integer, int[]> dayMarks =
                    com.bai.bookshelf.util.CheckInDots.marks(monthList, preciseSeq);
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "loadMonth ym=" + yearMonth + " monthRows=" + monthList.size()
                        + " books=" + bookCount
                        + " listRows=" + rows.size()
                        + " db=" + (SystemClock.elapsedRealtime() - dbStart) + "ms");
            }
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                if (yearMonth != b.monthGrid.getYearMonth()) {
                    return; // 迟到结果：快速切月后网格已经翻走，别把旧月的点贴回来
                }
                monthBookTitles = bookTitles;
                this.todayChecked = todayChecked;
                b.monthGrid.setTodayChecked(todayChecked);
                // 节日表与打卡点一次合并：内容没变的日子不再重绑
                b.monthGrid.setMonthData(holidays, dayMarks);
                monthSnapshots.put(yearMonth, new MonthSnapshot(dayMarks, holidays));
                prefetchAdjacentMonths(yearMonth);
                renderSummary(summary, streak, todayChecked, roundDays);
                for (CheckInAdapter.Row row : rows) {
                    row.pending = pendingOps.contains(row.book.id + ":" + day);
                }
                adapter.submit(rows, day, todayKey);
                monthDayAdapter.submit(monthDays, todayKey);
                readingEmpty = !anyReading;
                dayEmpty = anyReading && rows.isEmpty();
                renderEmptyState();
                updateMonthListVisibility();
                restoreMonthChrome();
                refreshHolidaysForMonth(y);
            });
        });
    }

    // ==================== 交互 ====================

    @Override
    public void onSelectDay(int dayKey) {
        selectedDayKey = dayKey;
        loadMonth(b.monthGrid.getYearMonth());
    }

    @Override
    public void onMoveToDay(int dayKey) {
        selectedDayKey = dayKey;
        b.monthGrid.setSelectedDay(dayKey);
        b.monthGrid.setYearMonth(CheckInMonthGrid.yearMonthOf(dayKey));
        loadMonth(b.monthGrid.getYearMonth());
    }

    @Override
    public void onFutureDay(int dayKey) {
        // 未来日现在可以选中查看，只做无障碍播报，不再弹拒绝 Toast
        if (b != null) {
            b.getRoot().announceForAccessibility(
                    CheckInUiState.dayText(dayKey) + "，未来日期，暂不能打卡");
        }
    }

    @Override
    public void onMonthChanged(int yearMonth, int selectedDayKey) {
        if (b == null) {
            return;
        }
        this.selectedDayKey = selectedDayKey;
        setToolbarMonth(yearMonth);
        // 缓存命中就在滑入动画的第一帧前把点铺好；列表/小结仍走 loadMonth
        MonthSnapshot snap = monthSnapshots.get(yearMonth);
        if (snap != null) {
            b.monthGrid.setTodayChecked(todayChecked);
            b.monthGrid.setMonthData(snap.holidays, snap.dayMarks);
        }
        loadMonth(yearMonth);
    }

    /** 5.2：上滑收周、下滑展开；5.5：MONTH 再下滑进逐日明细，DETAIL 上滑回整月 */
    @Override
    public void onVerticalSwipe(boolean up) {
        if (b == null || switcher == null || switcher.isAnimating()
                || switcher.isYearVisible()) {
            return;
        }
        CheckInCalendarState.Mode next = up
                ? CheckInCalendarState.afterUp(mode)
                : CheckInCalendarState.afterDown(mode);
        if (next == mode) {
            return; // WEEK 上滑 / DETAIL 下滑：没有下一步
        }
        applyMode(next, true);
        if (next == CheckInCalendarState.Mode.DETAIL) {
            // 月份可能刚切过，进明细时用当前月重建一次逐日行
            loadMonth(b.monthGrid.getYearMonth());
        }
    }

    @Override
    public void onToggle(int position, CheckInAdapter.Row row) {
        // 批量事务在途时不做单本读写，避免同一本书先批量加、再被单点撤掉
        if (b == null || row == null || batchPending) {
            return;
        }
        final int dayKey = selectedDayKey;
        final long bookId = row.book.id;
        final int seq = row.seq;
        if (!CheckInUiState.actionable(dayKey, todayKey) && !row.checked) {
            toast(CheckInUiState.rejectText(CheckInStore.Result.REJECTED_FUTURE));
            return;
        }
        // 在途去重：同一本书同一天连点只写一次；position 也不再作为身份，
        // 异步回来时行可能已经被别的提交移动/删掉，一律按 bookId 找
        final String opKey = bookId + ":" + dayKey;
        if (!pendingOps.add(opKey)) {
            return;
        }
        adapter.setPending(bookId, true);
        final long clickAt = BuildConfig.DEBUG ? SystemClock.elapsedRealtime() : 0L;
        final int today = todayKey;
        final int yearMonth = b.monthGrid.getYearMonth();
        final int y = CheckInMonthGrid.yearOf(yearMonth);
        final int m = CheckInMonthGrid.monthOf(yearMonth);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final CheckInStore.Result result =
                    CheckInStore.toggle(app.db(), row.book, dayKey, true, seq);
            if (result != CheckInStore.Result.ADDED
                    && result != CheckInStore.Result.REMOVED) {
                app.ui(() -> {
                    pendingOps.remove(opKey);
                    if (b != null) {
                        adapter.setPending(bookId, false);
                    }
                    toast(CheckInUiState.rejectText(result));
                });
                return;
            }
            final boolean added = result == CheckInStore.Result.ADDED;
            final int[] mark;
            final int newReadDays;
            final boolean hideRow;
            final int[] summary;
            final int[] streak;
            final int roundDays;
            final boolean todayChecked;
            final Book fresh;
            try {
                // 写入会提升/改写会话与汇总字段：一律按库里最新对象重算，别用点击时抓的行
                Book loaded = app.db().bookDao().getById(bookId);
                fresh = loaded != null ? loaded : row.book;
                List<CheckIn> dayRows = app.db().checkInDao().getByDay(dayKey);
                mark = dayMark(dayRows, dayKey, preciseSeqOf(app, dayRows));
                // 天数走唯一口径重算：精确轮按日集合去重，模糊轮仍是区间值（不受勾选影响）
                ReadingRecords.Record current = currentRecord(fresh);
                int recorded = app.db().checkInDao().countRoundDays(bookId, seq);
                newReadDays = current != null && current.mode == ReadingRecords.MODE_PRECISE
                        && current.seq == seq
                        ? recorded : ReadingRecords.readDays(current, null, null);
                // 撤销掉本轮最早那天后，选中日可能落到开读日之前：该行应消失
                hideRow = CheckInUiState.hiddenBeforeStart(added, dayKey,
                        CheckInStore.earliestCheckInKey(fresh, seq, recorded));
                // 小结随这次写入一起算完：不再另起一次 IO 重查整月
                summary = CheckInStore.monthSummary(app.db().checkInDao().getInRange(
                        DateKeys.keyOf(y, m, 1),
                        DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m))), y, m);
                streak = CheckInStore.streakDaysOf(
                        app.db().checkInDao().distinctDaysUpTo(today), today);
                todayChecked = app.db().checkInDao().countOnDay(today) > 0;
                List<Book> reading = new ArrayList<>();
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    if (book.readStatus == Book.READING) {
                        reading.add(book);
                    }
                }
                List<Long> ids = new ArrayList<>();
                for (Book book : reading) {
                    ids.add(book.id);
                }
                List<BookSeqStat> stats = ids.isEmpty()
                        ? new ArrayList<>() : app.db().checkInDao().statsForBooks(ids);
                roundDays = selectedRoundDays(reading, dayRows, stats);
            } catch (Exception ignored) {
                app.ui(() -> {
                    pendingOps.remove(opKey);
                    if (b != null) {
                        adapter.setPending(bookId, false);
                    }
                });
                return;
            }
            app.ui(() -> {
                pendingOps.remove(opKey);
                if (b == null) {
                    return;
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "toggle book=" + bookId + " day=" + dayKey + " result=" + result
                            + " latency=" + (SystemClock.elapsedRealtime() - clickAt) + "ms");
                }
                // 请求途中换了日期：结果不属于当前列表，别按旧行改
                if (adapter.getDayKey() != dayKey) {
                    adapter.setPending(bookId, false);
                    loadMonth(b.monthGrid.getYearMonth());
                    return;
                }
                b.getRoot().performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                // 手动改过任意一行后，上一次批量撤销的目标就不再可信
                clearBatchUndo();
                boolean applied;
                if (hideRow) {
                    applied = adapter.removeRowByBook(bookId);
                    dayEmpty = adapter.getItemCount() == 0;
                    renderEmptyState();
                    updateMonthListVisibility();
                } else {
                    applied = adapter.applyToggleResult(bookId, added, newReadDays, fresh) >= 0;
                }
                if (!applied) {
                    // 行已被别的刷新换掉：整页重取一次，避免静默丢状态
                    loadMonth(b.monthGrid.getYearMonth());
                    return;
                }
                b.monthGrid.setDayMarks(dayKey, mark[0], mark[1]);
                this.todayChecked = todayChecked;
                b.monthGrid.setTodayChecked(todayChecked);
                renderDayActions();
                renderSummary(summary, streak, todayChecked, roundDays);
                invalidateMonthSnapshot(b.monthGrid.getYearMonth());
                // 第 12 项：桌面小组件的「今日已打卡」跟着这次写入变
                com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(requireContext());
                // 打卡与静默撤销都不再弹撤销条（Snackbar）；数据仍可反向 toggle 回退
            });
        });
    }

    /** 单日批量：把当天所有「未打卡」的在读书一次写进同一个事务 */
    private void batchCheckIn() {
        if (b == null || batchPending || adapter == null) {
            return;
        }
        final int dayKey = selectedDayKey;
        if (CheckInUiState.dayActionButton(dayKey, todayKey) == null) {
            return;
        }
        final List<Book> targets = new ArrayList<>();
        for (CheckInAdapter.Row row : adapter.rows()) {
            if (!row.checked && !row.pending) {
                targets.add(row.book);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        batchPending = true;
        renderDayActions();
        final long clickAt = BuildConfig.DEBUG ? SystemClock.elapsedRealtime() : 0L;
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final CheckInStore.DayBatchResult result =
                    CheckInStore.addDayForBooks(app.db(), targets, dayKey, todayKey);
            app.ui(() -> {
                batchPending = false;
                if (b == null) {
                    return;
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "batch day=" + dayKey + " added=" + result.added
                            + " skipped=" + result.skipped()
                            + " latency=" + (SystemClock.elapsedRealtime() - clickAt) + "ms");
                }
                if (!result.addedBookIds.isEmpty()) {
                    showBatchUndo(result.addedBookIds, dayKey);
                }
                toast(CheckInUiState.batchDoneText(result.added, result.skipped()));
                invalidateMonthSnapshot(b.monthGrid.getYearMonth());
                com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(requireContext());
                loadMonth(b.monthGrid.getYearMonth());
            });
        });
    }

    private void showBatchUndo(List<Long> bookIds, int dayKey) {
        if (b == null) {
            return;
        }
        batchUndoIds = new ArrayList<>(bookIds);
        batchUndoDayKey = dayKey;
        final long generation = ++batchUndoGeneration;
        showDayUndo();
        uiHandler.postDelayed(() -> {
            if (generation == batchUndoGeneration) {
                clearBatchUndo();
            }
        }, BATCH_UNDO_MS);
    }

    private void clearBatchUndo() {
        batchUndoIds = new ArrayList<>();
        batchUndoGeneration++;
        if (b != null) {
            hideDayUndo();
        }
    }

    /** 只撤销刚批量加进去的那几本，不动用户此前已有的打卡 */
    private void undoBatchCheckIn() {
        if (b == null || batchUndoIds.isEmpty()) {
            return;
        }
        final List<Long> ids = new ArrayList<>(batchUndoIds);
        final int dayKey = batchUndoDayKey;
        batchUndoIds = new ArrayList<>();
        batchUndoGeneration++;
        hideDayUndo();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final int removed = CheckInStore.removeDayForBooks(app.db(), ids, dayKey);
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                toast(CheckInUiState.batchUndoText(removed));
                invalidateMonthSnapshot(b.monthGrid.getYearMonth());
                com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(requireContext());
                loadMonth(b.monthGrid.getYearMonth());
            });
        });
    }

    @Override
    public void onBackfillLongPress(int dayKey) {
        toast(CheckInUiState.backfillHint(dayKey));
    }

    private void toast(String text) {
        if (getContext() != null) {
            Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 返回 / 切 Tab 的刷新 ====================

    /** MainActivity 的统一可见性钩子：切 Tab 与二级页返回都走这里（节流合并重复调用） */
    @Override
    public void onTabShown() {
        reloadCurrentMonth();
    }

    // ==================== 切 Tab 内容层动效（v5.9.4） ====================

    @Override
    public void animateIn() {
        View content = b == null ? null : b.viewSwitchHost;
        if (content == null) {
            return;
        }
        content.animate().cancel();
        content.setTranslationY(6 * getResources().getDisplayMetrics().density);
        content.animate().translationY(0f)
                .setDuration(Motion.BASE)
                .setInterpolator(Motion.STANDARD)
                .start();
    }

    @Override
    public void settleContent() {
        View content = b == null ? null : b.viewSwitchHost;
        if (content == null) {
            return;
        }
        content.animate().cancel();
        content.setTranslationY(0f);
    }

    /** 详情页改完书状态回到本页时，Fragment 实例的旧数据必须重取 */
    @Override
    public void onResume() {
        super.onResume();
        // 别的页面可能改过书/打卡：跨月快照一律作废，等本页重新取
        monthSnapshots.evictAll();
        reloadCurrentMonth();
    }

    /** MainActivity 用 show/hide 复用四个 Tab，切回来时 onResume 不会再走一遍 */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            reloadCurrentMonth();
        }
    }

    /** 月视图节日表刷新：网络结果只回贴到仍在显示的那一年 */
    private void refreshHolidaysForMonth(int year) {
        if (b == null || getContext() == null) {
            return;
        }
        HolidayStore.refreshIfNeeded(requireContext(), year, (y, cal) -> {
            if (b == null) {
                return;
            }
            if (CheckInMonthGrid.yearOf(b.monthGrid.getYearMonth()) == y) {
                b.monthGrid.setHolidays(cal);
            }
        });
    }

    /** 年视图节日表刷新：CheckInYearView 自己会忽略年份对不上的迟到回调 */
    private void refreshHolidaysForYear(int year) {
        if (b == null || getContext() == null) {
            return;
        }
        HolidayStore.refreshIfNeeded(requireContext(), year, (y, cal) -> {
            if (b != null) {
                yearPanel.refreshHolidays();
            }
        });
    }

    private void reloadCurrentMonth() {
        if (b == null || !isAdded()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastReloadMs < 300L) {
            return; // 首载 / 连续切 Tab 的重复刷新并掉
        }
        lastReloadMs = now;
        todayKey = DateKeys.todayKey();
        b.monthGrid.refreshTodayKey(); // 只更新今天/未来分界，不动选中日
        setToolbarMonth(b.monthGrid.getYearMonth()); // 跨天后相对时间/年份归属可能变
        loadMonth(b.monthGrid.getYearMonth());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        midnightHandler.removeCallbacks(midnightTask);
        uiHandler.removeCallbacksAndMessages(null);
        monthSnapshots.evictAll();
        batchUndoIds = new ArrayList<>();
        batchUndoGeneration++;
        // 视图重建后两行文案都是空的，去重基准要跟着清掉
        dayActionText = "";
        if (dayActionAnim != null) {
            dayActionAnim.cancel();
            dayActionAnim = null;
        }
        if (b != null) {
            b.monthGrid.setCollapseProgressListener(null);
            b.monthGrid.setListener(null);
        }
        if (adapter != null) {
            adapter.setListener(null);
        }
        if (monthDayAdapter != null) {
            monthDayAdapter.setListener(null);
        }
        b = null;
    }

    /** 网格下方图例：圈 / 实心点 / 半实心点 / 方框各指什么，下面再补一句点的语义 */
    private void renderLegend() {
        CheckInLegendView.fill(b.llCheckinLegend,
                com.bai.bookshelf.util.CheckInLegend.monthItems());
        yearPanel.renderLegend();
        b.tvLegendNote.setText(com.bai.bookshelf.util.CheckInLegend.monthNote());
        // 收周时图例被折叠进度隐藏，这里不要在重新渲染时又亮起来
        b.tvLegendNote.setVisibility(mode == CheckInCalendarState.Mode.MONTH
                ? View.VISIBLE : View.GONE);
    }
}
