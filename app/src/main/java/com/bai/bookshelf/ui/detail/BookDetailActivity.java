package com.bai.bookshelf.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ReadingRecords;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 书籍详情页：展示书目与管理信息，提供 编辑 / 借出归还 / 删除 操作。
 *
 * 借出规则（v2.1）：
 * - 单本书：底部「借出/归还」按钮操作书本身（第 1 本 = 主记录）；
 * - 多本书：底部不再显示借出按钮，逐本信息里每一本（含第 1 本）行尾各有一个借出/归还按钮，
 *   借出记录通过 borrow_records.copyIndex 挂到具体某一本，互不干扰。
 */
public class BookDetailActivity extends BaseActivity {

    public static final String EXTRA_BOOK = "book";

    private ActivityBookDetailBinding b;
    private Book book;
    private String locationName = "未设置地点";
    /** 已加载的封面标识（url|书名）：reload 重绘时封面没变就不重复请求 Glide */
    private String loadedCoverKey = "";
    /** v5.8.14：封面主色（0 = 还在后台取），按 地址|书名|主题 失效 */
    private int coverAccent;
    private String accentKey = "";
    private boolean accentLoading;
    /** v5.8.15：共享元素转场期间不做视差，否则封面会被拽离转场路径 */
    private boolean transitionRunning;
    private int heroFadeRange = -1;
    /** 滚动每帧都要用，主题色只解析一次 */
    private int toolbarTitleColor;

    /** 分类展示串「一级-二级」（v4.9，reload 时解析好再给 bind 用） */
    private String categoryDisplay = "";
    private List<BorrowRecord> openRecords = new ArrayList<>(); // 当前所有未归还的借出记录（含 copyIndex）
    private List<BookCopy> copies = new ArrayList<>();
    private Map<Long, String> copyLocNames = new HashMap<>();   // 副本地点 id → 名称
    private List<NoteImage> noteImages = new ArrayList<>();     // 备注图片（默认折叠）
    private final DetailNoteImages noteImagesView = new DetailNoteImages();
    private final BookNoteBlock bookNoteBlock = new BookNoteBlock();
    private final DetailTags tagsView = new DetailTags();
    private List<Excerpt> excerpts = new ArrayList<>();         // 摘抄（v4.50，详情页只预览前 2 条）
    private boolean manageLendNoteExpanded = false;               // 单本借出备注是否展开
    private boolean manageLendImagesExpanded = false;           // 单本借出备注图片是否展开
    private Map<Integer, List<NoteImage>> copyLendImages = new HashMap<>(); // 第 N 本当前借出记录的备注图片

    /** v5.8.5：书架封面共享元素过渡的名字（没有就按普通方式打开） */
    public static final String EXTRA_TRANSITION_NAME = "transition_name";

    /** 借出 ⇄ 归还流程（登记弹窗、借阅人、备注图片） */
    private DetailLending lending;
    /** 逐本信息区（第 N 本的借还按钮、借出备注与图片） */
    private DetailCopyRows copyRows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityBookDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setupSharedElementTransition();
        b.scrollDetail.setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener)
                        (v, sx, sy, osx, osy) -> applyScrollEffects(sy));
        b.toolbar.setNavigationOnClickListener(v -> finishAfterTransition());
        // 系统返回键也走反向共享元素（默认 finish() 会跳过返回动画）
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        finishAfterTransition();
                    }
                });
        InsetsHelper.applyBottomInset(b.bottomBar);

        book = extraBook();
        if (book == null) {
            finish();
            return;
        }
        lending = new DetailLending(this, new DetailLending.Host() {
            @Override
            public Book book() {
                return book;
            }

            @Override
            public List<BorrowRecord> openRecords() {
                return openRecords;
            }

            @Override
            public List<BookCopy> copies() {
                return copies;
            }

            @Override
            public void reload() {
                BookDetailActivity.this.reload();
            }

            @Override
            public void refreshReminders() {
                com.bai.bookshelf.util.BorrowReminder.refresh(BookDetailActivity.this);
            }

            @Override
            public void ensureNotifyPermission() {
                BookDetailActivity.this.ensureNotifyPermission();
            }
        });
        copyRows = new DetailCopyRows(this, b, new DetailCopyRows.Host() {
            @Override
            public Book book() {
                return book;
            }

            @Override
            public List<BookCopy> copies() {
                return copies;
            }

            @Override
            public List<BorrowRecord> openRecords() {
                return openRecords;
            }

            @Override
            public Map<Long, String> copyLocNames() {
                return copyLocNames;
            }

            @Override
            public String locationName() {
                return locationName;
            }

            @Override
            public Map<Integer, List<NoteImage>> copyLendImages() {
                return copyLendImages;
            }

            @Override
            public boolean lendingDisabled() {
                return isSourceReturned();
            }

            @Override
            public void showLendDialog(int copyIndex) {
                lending.showLendDialog(copyIndex);
            }

            @Override
            public void returnCopy(int copyIndex, BorrowRecord open) {
                lending.returnCopy(copyIndex, open);
            }
        });
        // 先用 Intent 里的数据把详情画出来（首帧立即有内容），
        // 地点名/借出记录/逐本/备注图后台刷新后再重绘。
        bind();

        b.btnEdit.setOnClickListener(v -> {
            Intent intent = new Intent(this, BookEditActivity.class);
            intent.putExtra(BookEditActivity.EXTRA_BOOK, book);
            startActivity(intent);
        });
        // 单本时的借出按钮（多本时隐藏，改为逐本信息里的行内按钮）
        b.btnLend.setOnClickListener(v -> lending.toggleLend());
        b.btnReturnSource.setOnClickListener(v -> confirmReturnToSource());
        b.btnMore.setOnClickListener(v -> showMoreActions());
        // v5.8.31：状态主按钮 + 弃读（触感与书架 Tab 一致）
        b.btnReadAction.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            onReadAction();
        });
        b.btnReadDrop.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            onReadDrop();
        });
        b.rowBorrowHistory.setOnClickListener(v -> {
            Intent intent = new Intent(this, BorrowHistoryActivity.class);
            intent.putExtra(BorrowHistoryActivity.EXTRA_BOOK_ID, book.id);
            startActivity(intent);
        });
        b.tvNote.setOnClickListener(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload(); // 编辑返回后刷新
    }

    @SuppressWarnings("deprecation")
    /**
     * 书架封面 → 详情 hero 的共享元素（v5.8.5）。
     *
     * <p>封面是异步加载的，所以用 postponeEnterTransition + 300ms 兜底：
     * 无论图片有没有回来，详情页都不会被卡住；过渡结束后把圆角从列表的 8dp
     * 补间到详情的 12dp。
     */
    private void setupSharedElementTransition() {
        final String name = getIntent().getStringExtra(EXTRA_TRANSITION_NAME);
        if (name == null || name.isEmpty()) {
            return;
        }
        androidx.core.view.ViewCompat.setTransitionName(b.ivCover, name);
        transitionRunning = true;
        androidx.core.app.ActivityCompat.postponeEnterTransition(this);
        setEnterSharedElementCallback(new android.app.SharedElementCallback() {
            @Override
            public void onSharedElementEnd(java.util.List<String> names,
                                           java.util.List<android.view.View> views,
                                           java.util.List<android.view.View> snapshots) {
                transitionRunning = false;
                animateCoverRadius();
            }
        });
        // 下一帧就放行：封面图还在异步加载，但共享元素视图已经就位，
        // 等 Glide 会白白多花 300ms（实测 Displayed 485ms）。
        b.ivCover.post(() -> androidx.core.app.ActivityCompat
                .startPostponedEnterTransition(BookDetailActivity.this));
    }

    /** 圆角从列表的 8dp 补间到 hero 的 12dp */
    private void animateCoverRadius() {
        android.view.View parent = (android.view.View) b.ivCover.getParent();
        if (!(parent instanceof androidx.cardview.widget.CardView)) {
            return;
        }
        final androidx.cardview.widget.CardView card = (androidx.cardview.widget.CardView) parent;
        float from = getResources().getDimension(R.dimen.radius_sm);
        float to = getResources().getDimension(R.dimen.radius_md);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration(180);
        anim.addUpdateListener(a -> card.setRadius((Float) a.getAnimatedValue()));
        anim.start();
    }

    private Book extraBook() {
        Book b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b = getIntent().getSerializableExtra(EXTRA_BOOK, Book.class);
        } else {
            b = (Book) getIntent().getSerializableExtra(EXTRA_BOOK);
        }
        // v3.0：Intent 传来的 Book 同样可能带 null 字段（老数据），统一清洗后再用
        Book normalized = com.bai.bookshelf.util.BookSanitizer.normalize(b);
        if (normalized != null) {
            // 单本详情才需要按记录校正汇总，列表路径不解析 JSON
            ReadingRecords.refreshSummary(normalized);
            com.bai.bookshelf.util.BorrowSourceRecords.refreshSummary(normalized);
        }
        return normalized;
    }

    /** 重新从数据库读取最新数据（含地点名、借出记录、逐本信息、备注图片） */
    /**
     * 阅读状态行（v5.8.9 进度滑杆 / v5.8.31 副文案）：
     * 在读 →「读到 N% · 点按修改」；已读 →「M月D日读完 · 第 K 次」；
     * 弃读 →「停在 N% · M月D日弃读」；未读 → 整行隐藏。
     */
    private void bindReadingProgress() {
        final int status = book == null ? Book.READ_NONE : book.readStatus;
        final boolean reading = status == Book.READING;
        final int p = Math.max(0, Math.min(100, book == null ? 0 : book.readingProgress));
        List<ReadingRecords.Record> records =
                book == null ? new ArrayList<>() : ReadingRecords.load(book);
        String text;
        if (reading) {
            text = p > 0 ? "读到 " + p + "% · 点按修改" : "记录阅读进度（点按设置）";
        } else if (status == Book.READ_DONE) {
            ReadingRecords.Record done = ReadingRecords.lastDone(records);
            int times = 0;
            for (ReadingRecords.Record r : records) {
                if (r.status == Book.READ_DONE) {
                    times++;
                }
            }
            String day = mdLabel(done == null ? book.readingFinishDate : done.endDate);
            text = day.isEmpty() ? "已读完 · 第 " + times + " 次"
                    : day + "读完 · 第 " + times + " 次";
        } else if (status == Book.READ_DROPPED) {
            ReadingRecords.Record last = records.isEmpty()
                    ? null : ReadingRecords.lastOf(records);
            String day = mdLabel(last == null ? "" : last.endDate);
            text = day.isEmpty() ? "读了一半就放下了"
                    : "停在 " + p + "% · " + day + "弃读";
        } else {
            text = "";
        }
        b.tvReadingProgress.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        b.tvReadingProgress.setText(text);
        b.tvReadingProgress.setOnClickListener(reading ? v -> showProgressDialog(p) : null);
        // v5.8.14：进度条跟着封面主色走（未取到色时先用主题主色）
        b.pbReadingProgress.setVisibility(reading && p > 0 ? View.VISIBLE : View.GONE);
        b.pbReadingProgress.setProgress(p);
        b.pbReadingProgress.setProgressTintList(ColorStateList.valueOf(
                coverAccent != 0 ? coverAccent
                        : ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY)));
    }

    /** yyyy-MM-dd → 「M月d日」；缺日期返回空串让上层决定怎么措辞 */
    private static String mdLabel(String date) {
        int key = com.bai.bookshelf.util.DateKeys.parseKey(date);
        return key <= 0 ? "" : (key / 100 % 100) + "月" + (key % 100) + "日";
    }

    /** v5.8.31：底栏状态主按钮的文案 + 弃读按钮只在「在读」时出现 */
    private void bindReadAction() {
        // 借来的书不进状态机（借阅逻辑保持原样），底栏让给「归还 / 借出」
        final boolean borrowed = book != null && book.ownType == Book.OWN_BORROW;
        b.btnReadAction.setVisibility(borrowed ? View.GONE : View.VISIBLE);
        String label;
        switch (book == null ? Book.READ_NONE : book.readStatus) {
            case Book.READING:
                label = "读完";
                break;
            case Book.READ_DROPPED:
                label = "再次阅读";
                break;
            case Book.READ_DONE:
                label = "再读一遍";
                break;
            default:
                label = "开始阅读";
                break;
        }
        b.btnReadAction.setText(label);
        b.btnReadAction.setContentDescription(label);
        b.btnReadDrop.setVisibility(!borrowed
                && book != null && book.readStatus == Book.READING
                ? View.VISIBLE : View.GONE);
    }

    /** v5.8.31：状态主按钮按当前状态分派到 ReadingFlow；在读没读满先问一句，不静默记完 */
    private void onReadAction() {
        final int status = book.readStatus;
        if (status == Book.READING && book.readingProgress < 100) {
            offerFinish();
            return;
        }
        switch (status) {
            case Book.READING:
                applyFlow(null, b -> com.bai.bookshelf.util.ReadingFlow.finish(b, today()),
                        "已标记为「已读」");
                break;
            case Book.READ_DROPPED:
            case Book.READ_DONE:
                applyFlow("这本书已经在读了",
                        b -> com.bai.bookshelf.util.ReadingFlow.restart(b, today()),
                        status == Book.READ_DONE ? "已开始重读，这是新一轮" : "重新开始阅读");
                break;
            case Book.READ_NONE:
            default:
                applyFlow("这本书已经在读了",
                        b -> com.bai.bookshelf.util.ReadingFlow.start(b, today()),
                        "已开始阅读");
                break;
        }
    }

    /** v5.8.31：弃读——保留停在百分之几，不写「读完日期」 */
    private void onReadDrop() {
        applyFlow(null, b -> com.bai.bookshelf.util.ReadingFlow.drop(b, today()),
                "已标记为「弃读」");
    }

    private interface FlowOp {
        boolean run(Book book);
    }

    /** 跑一个会话动作：写库 → reload → 8 秒撤销（撤销 = 还原动作前的整本书） */
    private void applyFlow(String alreadyMessage, FlowOp op, String doneMessage) {
        BookshelfApp app = BookshelfApp.get();
        final Book before = com.bai.bookshelf.util.BookSnapshot.copy(book);
        app.io(() -> {
            boolean changed;
            try {
                changed = op.run(book);
            } catch (Exception e) {
                changed = false;
            }
            if (!changed) {
                app.ui(() -> {
                    if (alreadyMessage != null) {
                        android.widget.Toast.makeText(this, alreadyMessage,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            try {
                app.db().bookDao().update(book);
            } catch (Exception e) {
                app.ui(() -> android.widget.Toast.makeText(this,
                        "保存失败：" + e.getMessage(),
                        android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            app.ui(() -> {
                reload();
                com.bai.bookshelf.util.UndoBar.offer(
                        findViewById(android.R.id.content), doneMessage,
                        before == null ? null : () -> app.io(() -> {
                            try {
                                app.db().bookDao().update(before);
                            } catch (Exception ignored) {
                                // 撤销失败只丢这次撤销，不影响已落库的状态
                            }
                            app.ui(this::reload);
                        }), null);
            });
        });
    }

    private static String today() {
        return com.bai.bookshelf.util.DateKeys.formatKey(
                com.bai.bookshelf.util.DateKeys.todayKey());
    }

    /** 封面主色 → hero 晕染 + 底部细线 + 阅读进度条；取色在后台，失败按主题色 */
    private void bindCoverAccent() {
        final String key = book.coverUrl + "|" + book.title + "|"
                + ThemeManager.current(this);
        int cached = com.bai.bookshelf.util.CoverColor.cached(this, book);
        if (cached != 0) {
            coverAccent = cached;
            accentKey = key;
        } else if (!key.equals(accentKey)) {
            coverAccent = 0;
            accentKey = key;
        }
        applyCoverAccent();
        if (coverAccent != 0 || accentLoading) {
            return;
        }
        accentLoading = true;
        final Book target = book;
        final String wanted = key;
        BookshelfApp.get().io(() -> {
            int color = com.bai.bookshelf.util.CoverColor.of(this, target);
            runOnUiThread(() -> {
                accentLoading = false;
                if (book != null && wanted.equals(book.coverUrl + "|" + book.title + "|"
                        + ThemeManager.current(this))) {
                    coverAccent = color;
                    applyCoverAccent();
                }
            });
        });
    }

    private void applyCoverAccent() {
        boolean night = ThemeManager.current(this) == ThemeManager.THEME_NIGHT;
        int base = ThemeManager.resolveColor(this, R.attr.primary_container);
        int scrim = coverAccent == 0 ? base
                : androidx.core.graphics.ColorUtils.blendARGB(
                        base, coverAccent, night ? 0.30f : 0.22f);
        b.vHeroScrim.setBackgroundColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(scrim, 217));
        b.vHeroLine.setBackgroundColor(coverAccent == 0
                ? ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_SECONDARY)
                : androidx.core.graphics.ColorUtils.setAlphaComponent(coverAccent, 180));
        if (b.pbReadingProgress.getVisibility() == View.VISIBLE) {
            b.pbReadingProgress.setProgressTintList(ColorStateList.valueOf(
                    coverAccent != 0 ? coverAccent
                            : ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY)));
        }
    }

    /**
     * 滚动效果（v5.8.15）：hero 封面与模糊底按 0.9 倍速跟手（内容走 1、封面走 0.9），
     * 工具栏标题从「书籍详情」交叉淡入成书名。共享元素转场期间跳过，避免把封面拽离转场路径。
     */
    private void applyScrollEffects(int scrollY) {
        if (transitionRunning) {
            return;
        }
        int heroH = b.hero.getHeight();
        if (heroH <= 0) {
            return;
        }
        float offset = Math.min(scrollY, heroH) * 0.1f;
        b.ivHeroBg.setTranslationY(offset);
        b.heroCoverCard.setTranslationY(offset);
        if (heroFadeRange < 0) {
            heroFadeRange = Math.max(1, heroH - Math.round(
                    56 * getResources().getDisplayMetrics().density));
        }
        float fade = Math.max(0f, Math.min(1f, scrollY / (float) heroFadeRange));
        b.tvToolbarTitle.setAlpha(fade);
        b.tvToolbarTitle.setVisibility(fade > 0.02f ? View.VISIBLE : View.INVISIBLE);
        if (toolbarTitleColor == 0) {
            toolbarTitleColor = ThemeManager.resolveColor(this,
                    com.google.android.material.R.attr.colorOnPrimary);
        }
        b.toolbar.setTitleTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                toolbarTitleColor, Math.round(255 * (1 - fade))));
    }

    /**
     * 进度弹窗（v5.8.19）：填了总页数就能直接写「读到第几页」，
     * 页码与进度条双向联动；没填页数时只有进度条，并提示去哪儿补页数。
     */
    private void showProgressDialog(int current) {
        final int totalPages = book.pages;
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        final android.widget.TextView label = new android.widget.TextView(this);
        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(100);
        bar.setProgress(current);
        final android.widget.EditText pageEdit;
        // 页码与进度条互相回写，用这个开关挡住来回触发的死循环
        final boolean[] syncing = {false};
        if (totalPages > 0) {
            pageEdit = new android.widget.EditText(this);
            pageEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            pageEdit.setHint("读到第几页");
            pageEdit.setText(String.valueOf(
                    com.bai.bookshelf.util.ReadingProgress.pageForPercent(current, totalPages)));
        } else {
            pageEdit = null;
        }
        Runnable render = () -> {
            int percent = bar.getProgress();
            label.setText(totalPages > 0
                    ? "第 " + com.bai.bookshelf.util.ReadingProgress
                            .pageForPercent(percent, totalPages)
                            + " 页 / 共 " + totalPages + " 页 · " + percent + "%"
                    : percent + "%");
        };
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int value, boolean fromUser) {
                render.run();
                if (pageEdit != null && !syncing[0]) {
                    syncing[0] = true;
                    pageEdit.setText(String.valueOf(
                            com.bai.bookshelf.util.ReadingProgress
                                    .pageForPercent(value, totalPages)));
                    pageEdit.setSelection(pageEdit.getText().length());
                    syncing[0] = false;
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        if (pageEdit != null) {
            pageEdit.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int s0, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int s0, int b1, int c1) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (syncing[0]) {
                        return;
                    }
                    int page;
                    try {
                        page = Integer.parseInt(s.toString().trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    page = Math.max(0, Math.min(totalPages, page));
                    syncing[0] = true;
                    bar.setProgress(com.bai.bookshelf.util.ReadingProgress
                            .percentForPage(page, totalPages));
                    render.run();
                    syncing[0] = false;
                }
            });
        }
        render.run();
        wrap.addView(label);
        if (pageEdit != null) {
            wrap.addView(pageEdit);
        }
        wrap.addView(bar);
        if (totalPages <= 0) {
            android.widget.TextView hint = new android.widget.TextView(this);
            hint.setTextSize(12f);
            hint.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(
                    this, R.attr.on_surface_variant));
            hint.setText("这本书还没填总页数，暂时只能按百分比记。填了页数（编辑页 → 页数）就能直接写「读到第几页」。");
            wrap.addView(hint);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("阅读进度")
                .setView(wrap)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("保存", (d, w) -> saveProgress(bar.getProgress()))
                .show();
    }

    private void saveProgress(int percent) {
        final int value = Math.max(0, Math.min(100, percent));
        final boolean alreadyDone = book.readStatus == Book.READ_DONE;
        final BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                book.readingProgress = value;
                // v5.8.31：进度同时写进「进行中的那一场」会话——汇总字段现在由
                // syncTo 从会话推导，不落进会话的话下一次状态动作就会把它冲回 0
                java.util.List<ReadingRecords.Record> recs = ReadingRecords.load(book);
                ReadingRecords.Record ongoing = ReadingRecords.ongoingReading(recs);
                if (ongoing != null) {
                    ongoing.progress = value;
                    ReadingRecords.syncTo(book, recs);
                }
                book.updatedAt = System.currentTimeMillis();
                app.db().bookDao().update(book);
            } catch (Exception e) {
                app.ui(() -> android.widget.Toast.makeText(this,
                        "保存失败：" + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            app.ui(() -> {
                reload();
                // v5.8.19：进度推到 100% 但状态还没改，就顺势问一句要不要标已读
                if (value >= 100 && !alreadyDone) {
                    offerFinish();
                }
            });
        });
    }

    /** 读完了：提议标记已读，可以顺手去补一条阅读记录 */
    private void offerFinish() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("读完了？")
                .setMessage("进度已经到 100%，要把它标成「已读」吗？标记时会顺手记下读完的日期。")
                .setPositiveButton("标记已读", (d, w) -> markRead(false))
                .setNeutralButton("已读并补记录", (d, w) -> markRead(true))
                .setNegativeButton("先不", null)
                .show();
    }

    private void markRead(boolean openRecord) {
        BookshelfApp app = BookshelfApp.get();
        final Book before = com.bai.bookshelf.util.BookSnapshot.copy(book);
        app.io(() -> {
            // v5.8.31：改走会话状态机——有进行中的会话就收尾，没有则新开一场已读，
            // 不再就地改写别场的状态与日期
            com.bai.bookshelf.util.ReadingFlow.finish(book,
                    com.bai.bookshelf.util.DateKeys.formatKey(
                            com.bai.bookshelf.util.DateKeys.todayKey()));
            book.readingProgress = 100;
            book.updatedAt = System.currentTimeMillis();
            book.dirty = true;
            app.db().bookDao().update(book);
            app.ui(() -> {
                reload();
                com.bai.bookshelf.util.UndoBar.offer(
                        findViewById(android.R.id.content), "已标记为「已读」",
                        before == null ? null : () -> app.io(() -> {
                            app.db().bookDao().update(before);
                            app.ui(this::reload);
                        }), null);
                if (openRecord) {
                    Intent intent = new Intent(this, BookEditActivity.class);
                    intent.putExtra(BookEditActivity.EXTRA_BOOK, book);
                    startActivity(intent);
                }
            });
        });
    }

    private void reload() {
        DetailDataLoader.load(BookshelfApp.get(), book.id, data -> {
            if (data == null) { // 已被删除
                finish();
                return;
            }
            book = data.book;
            locationName = data.location == null ? "未设置地点" : data.location.name;
            openRecords = data.openRecords;
            copies = data.copies;
            copyLocNames = data.copyLocNames;
            noteImages = data.noteImages;
            excerpts = data.excerpts;
            copyLendImages = data.copyLendImages;
            categoryDisplay = data.categoryDisplay;
            // 借出过才显示「借出记录」入口
            b.rowBorrowHistory.setVisibility(data.recordCount > 0 ? View.VISIBLE : View.GONE);
            b.rowBorrowHistory.setText("借出记录（我借给别人 · " + data.recordCount + " 条）");
            bind();
        });
    }

    /** 把书籍信息渲染到界面 */
    private void bind() {
        b.tvTitle.setText(com.bai.bookshelf.util.BookText.displayTitle(book.title));
        // v5.8.15：工具栏标题随滚动淡入书名（reload 后也要按当前滚动位置补一次状态）
        b.tvToolbarTitle.setText(com.bai.bookshelf.util.BookText.displayTitle(book.title));
        b.scrollDetail.post(() -> applyScrollEffects(b.scrollDetail.getScrollY()));

        // hero：作者/译者/国家 + 状态 chips（书名与大信息量交给下面的两列信息块）
        List<String> authors = new ArrayList<>();
        addIfNotEmpty(authors, book.author);
        addIfNotEmpty(authors, book.translator);
        addIfNotEmpty(authors, book.country);
        String authorLine = joinWithDot(authors);
        b.tvHeroAuthor.setText(authorLine);
        b.tvHeroAuthor.setVisibility(authorLine.isEmpty() ? View.GONE : View.VISIBLE);
        // v5.7：来源借阅且已归还 → 藏书状态显示「电子书签」，来源 chip 隐藏
        boolean sourceReturned = isSourceReturned();
        if (sourceReturned) {
            bindHeroChip(b.tvHeroStock, StatusText.stock(Book.STOCK_BOOKMARK), false);
            b.tvHeroOwn.setVisibility(View.GONE);
        } else {
            bindHeroChip(b.tvHeroStock, StatusText.stock(book.stockStatus),
                    book.stockStatus == Book.STOCK_LENT);
            bindHeroChip(b.tvHeroOwn, StatusText.own(book.ownType), false);
        }
        bindHeroChip(b.tvHeroRead, StatusText.read(book.readStatus), false);
        // v5.7：平均评分（多次阅读记录的评分均值，0.5 步进）
        double avgRating = ReadingRecords.averageRating(ReadingRecords.load(book));
        b.tvHeroRating.setVisibility(avgRating > 0 ? View.VISIBLE : View.GONE);
        if (avgRating > 0) {
            bindHeroChip(b.tvHeroRating,
                    "★ " + ReadingRecords.formatRating(avgRating), false);
        }

        // 封面：统一走 CoverLoader（180ms 淡入 / 布面首字占位）。
        // v4.39：reload 重绘时封面没变就跳过——重复 into() 会让封面重新淡入，闪一下。
        String coverKey = book.coverUrl + "|" + book.title;
        if (!coverKey.equals(loadedCoverKey)) {
            loadedCoverKey = coverKey;
            com.bai.bookshelf.util.CoverLoader.load(b.ivCover, book);
            // v5.7 无障碍：TalkBack 聚焦封面时能报出是哪本书，而不是笼统的「书籍封面」
            b.ivCover.setContentDescription("《" + book.title + "》封面");
            // v4.30：hero 用封面做模糊底；v5.8.14 遮罩再叠一点封面主色
            com.bai.bookshelf.util.CoverLoader.loadBlurred(b.ivHeroBg, book);
        }
        bindCoverAccent();
        boolean multi = book.quantity > 1;
        boolean hasLent = !openRecords.isEmpty();

        // 系列（v4.53）：有系列时在书目信息上方给一条可点入口
        String seriesName = com.bai.bookshelf.util.BookSeries.name(book);
        if (seriesName.isEmpty()) {
            b.cardSeries.setVisibility(View.GONE);
        } else {
            String seriesVolume = com.bai.bookshelf.util.BookSeries.volumeLabel(book);
            b.tvSeriesInfo.setText(seriesVolume.isEmpty()
                    ? seriesName : seriesName + " · " + seriesVolume);
            b.cardSeries.setVisibility(View.VISIBLE);
            b.cardSeries.setOnClickListener(v -> {
                android.content.Intent intent = new android.content.Intent(this,
                        com.bai.bookshelf.ui.series.SeriesActivity.class);
                intent.putExtra(com.bai.bookshelf.ui.series.SeriesActivity.EXTRA_SERIES_NAME,
                        seriesName);
                startActivity(intent);
            });
        }

        // ===== 书目 / 藏书 / 我的书房（v5.6 抽到 DetailInfoBlocks） =====
        DetailInfoBlocks.bind(b, book, multi, hasLent, locationName, categoryDisplay,
                copies, openRecords);
        DetailRecordLists.bindReading(this, b, book, openRecords, copies);
        DetailRecordLists.bindBorrow(this, b, book);
        // v5.7：四个分区可折叠，折叠状态跨书沿用
        DetailSectionCollapse.bind(this, b.ivToggleInfo, b.llInfoGrid,
                DetailSectionCollapse.INFO, "书目信息");
        DetailSectionCollapse.bind(this, b.ivToggleCore, b.llCoreGrid,
                DetailSectionCollapse.CORE, "藏书信息");
        DetailSectionCollapse.bind(this, b.ivToggleManage, b.llManageGrid,
                DetailSectionCollapse.MANAGE, "我的书房");
        DetailSectionCollapse.bind(this, b.ivToggleRecords, b.containerReadingRecordsDetail,
                DetailSectionCollapse.RECORDS, "阅读记录");
        DetailSectionCollapse.bind(this, b.ivToggleBorrowRecords,
                b.containerBorrowRecordsDetail,
                DetailSectionCollapse.BORROW_RECORDS, "借入记录");
        View.OnClickListener readingCalendar = v -> showReadingCalendar();
        b.tvReadingRecordsTitle.setOnClickListener(readingCalendar);
        bindReadingProgress();
        bindReadAction();
        b.btnReadingCalendar.setOnClickListener(readingCalendar);
        b.containerReadingRecordsDetail.setOnClickListener(readingCalendar);
        bindSectionChips();

        // 单本借出备注与图片：独立于管理信息块，文本前 20 字折叠、图片默认折叠
        BorrowRecord open1 = DetailCopyInfo.openRecordForCopy(openRecords, 1);
        if (!multi && open1 != null) {
            BorrowDueBanner.bind(this, b.tvBorrowDue, book, open1, this::reload);
        } else {
            b.tvBorrowDue.setVisibility(View.GONE);
        }
        if (!multi && open1 != null && !open1.note.isEmpty()) {
            b.tvManageLendNote.setVisibility(View.VISIBLE);
            renderManageLendNote(open1.note);
        } else {
            b.tvManageLendNote.setVisibility(View.GONE);
        }
        List<NoteImage> manageLendImgs = open1 == null ? null : copyLendImages.get(1);
        if (!multi && manageLendImgs != null && !manageLendImgs.isEmpty()) {
            b.rowManageLendNoteImages.setVisibility(View.VISIBLE);
            b.containerManageLendNoteImages.setVisibility(
                    manageLendImagesExpanded ? View.VISIBLE : View.GONE);
            renderManageLendNoteImagesArea(manageLendImgs);
        } else {
            b.rowManageLendNoteImages.setVisibility(View.GONE);
            b.containerManageLendNoteImages.setVisibility(View.GONE);
            b.containerManageLendNoteImages.removeAllViews();
        }

        // 备注：单独成块，默认最多 3 行，点击展开
        bookNoteBlock.bind(b, book);

        // 备注图片：默认折叠，位于备注下方
        noteImagesView.bind(this, b, noteImages);

        // 摘抄与书评（v4.50）：短评 + 最新 2 条摘抄预览
        DetailExcerpts.bind(this, b, book, excerpts);

        // v5.7：有未归还的借阅记录 → 显示「归还来源」；电子书签状态下不可借出
        b.btnReturnSource.setVisibility(
                book.ownType == Book.OWN_BORROW
                        && com.bai.bookshelf.util.BorrowSourceRecords.hasOpen(book)
                        ? View.VISIBLE : View.GONE);
        // 逐本信息块：多本时按本铺开，第 1 本也在此显示（行尾带借出/收回按钮）；>3 本默认只显前 3 本
        copyRows.render(multi, sourceReturned);
        // 借出按钮文案随状态切换（单本才有此按钮）
        b.btnLend.setText(book.stockStatus == Book.STOCK_LENT ? "收回" : "借出");

        // 标签独立区域展示（不与管理信息混在一起）
        tagsView.bind(this, b, book, this::showFullTag);

        // v3.6：内容简介已停用（API 不再导入、布局中 tv_summary 永久 gone）
    }

    /**
     * 借阅到期横幅已抽到 {@link BorrowDueBanner}（v5.2）。
     * Android 13+：借出时若设了应还日期，就申请通知权限；拒绝则靠书架「超期」角标
     */
    private void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9101);
        }
    }

    /** 单本借出备注：超过 20 字默认截断，点击切换 */
    private void renderManageLendNote(String note) {        if (note.length() <= DetailCopyRows.LEND_NOTE_PREVIEW_MAX || manageLendNoteExpanded) {
            b.tvManageLendNote.setText("借出备注：" + note);
            b.tvManageLendNote.setOnClickListener(note.length() > DetailCopyRows.LEND_NOTE_PREVIEW_MAX
                    ? v -> {
                        manageLendNoteExpanded = false;
                        renderManageLendNote(note);
                    }
                    : null);
        } else {
            b.tvManageLendNote.setText("借出备注：" + note.substring(0, DetailCopyRows.LEND_NOTE_PREVIEW_MAX) + "…");
            b.tvManageLendNote.setOnClickListener(v -> {
                manageLendNoteExpanded = true;
                renderManageLendNote(note);
            });
        }
    }

    /** 单本借出备注图片入口与内容区 */
    private void renderManageLendNoteImagesArea(List<NoteImage> imgs) {
        b.rowManageLendNoteImages.setText(manageLendImagesExpanded
                ? "借出备注图片（" + imgs.size() + " 张）· 收起"
                : "借出备注图片（" + imgs.size() + " 张）· 展开");
        b.rowManageLendNoteImages.setOnClickListener(v -> {
            manageLendImagesExpanded = !manageLendImagesExpanded;
            renderManageLendNoteImagesArea(imgs);
        });
        if (manageLendImagesExpanded) {
            copyRows.renderImages(b.containerManageLendNoteImages, imgs);
        } else {
            b.containerManageLendNoteImages.removeAllViews();
        }
    }

    private void showFullTag(String fullTag) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("完整标签")
                .setMessage(fullTag)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ======================= 备注图片（默认折叠，UI 见 DetailNoteImages） =======================

    private void addIfNotEmpty(List<String> list, String value) {
        if (value != null && !value.trim().isEmpty()) {
            list.add(value.trim());
        }
    }

    private static String joinWithDot(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** hero 状态 chip：借出用黄铜底白字，其余用白底主色字 */
    private void bindHeroChip(TextView tv, String text, boolean emphasize) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
        if (emphasize) {
            tv.setBackgroundResource(R.drawable.bg_status_chip);
            tv.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.badge_borrowed)));
            tv.setTextColor(Color.WHITE);
        } else {
            tv.setBackgroundResource(R.drawable.bg_status_chip);
            tv.setBackgroundTintList(null);
            tv.setTextColor(ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY));
        }
    }

    /** 底栏「更多」：删除收在这里，红字，不与编辑同权重 */
    private void showMoreActions() {
        PopupMenu popup = new PopupMenu(this, b.btnMore);
        SpannableString delete = new SpannableString("删除此藏书");
        delete.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.danger)),
                0, delete.length(), 0);
        popup.getMenu().add(0, 2, 0, "分享书卡");
        popup.getMenu().add(0, 1, 1, delete);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                confirmDelete();
                return true;
            }
            if (item.getItemId() == 2) {
                DetailShare.shareBookCard(this, book, b.btnMore);
                return true;
            }
            return false;
        });
        popup.show();
    }

    // 借出 ⇄ 归还与借出登记见 DetailLending（v5.6 从本类抽出）

    /** 吸顶分区导航：概览 / 阅读 / 借阅 / 笔记只跳到当前存在的分区 */
    private void bindSectionChips() {
        b.chipOverview.setOnClickListener(v -> scrollDetailTo(b.hero));
        b.chipReading.setOnClickListener(v -> scrollDetailTo(b.cardReadingRecords));
        b.chipBorrow.setOnClickListener(v -> scrollDetailTo(b.cardBorrowRecords));

        View notesTarget = null;
        for (View candidate : new View[]{b.cardExcerpts, b.tvNote, b.cardTags}) {
            if (candidate.getVisibility() == View.VISIBLE) {
                notesTarget = candidate;
                break;
            }
        }
        b.chipNotes.setVisibility(notesTarget == null ? View.GONE : View.VISIBLE);
        if (notesTarget != null) {
            final View target = notesTarget;
            b.chipNotes.setOnClickListener(v -> scrollDetailTo(target));
        }
        b.chipReading.setVisibility(b.cardReadingRecords.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
        b.chipBorrow.setVisibility(b.cardBorrowRecords.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    private void scrollDetailTo(View target) {
        b.scrollDetail.post(() -> {
            android.graphics.Rect rect = new android.graphics.Rect();
            b.scrollDetail.offsetDescendantRectToMyCoords(target, rect);
            b.scrollDetail.smoothScrollTo(0, Math.max(0, rect.top));
        });
    }

    /** 本书的阅读日历（按阅读记录起止日期铺出每天） */
    private void showReadingCalendar() {
        Map<String, java.util.List<String>> titles =
                com.bai.bookshelf.util.ReadingCalendar.titlesByDay(book);
        Map<String, Integer> days = new java.util.LinkedHashMap<>();
        for (String day : titles.keySet()) {
            days.put(day, 1);
        }
        int[] ymd = com.bai.bookshelf.util.ReadingCalendar.latestYmd(days);
        com.bai.bookshelf.ui.stats.PurchaseCalendarSheet.show(this,
                getString(R.string.reading_calendar_title),
                getString(R.string.reading_calendar_book_summary, days.size()),
                days, titles,
                new com.bai.bookshelf.ui.stats.PurchaseCalendarSheet.Start(false, ymd[0], ymd[1]),
                com.bai.bookshelf.ui.stats.PurchaseCalendarSheet.readingLabels(),
                null);
    }

    /** 来源为借阅且已归还：不能再借出、也不再展示存放/来源 */
    private boolean isSourceReturned() {
        return com.bai.bookshelf.util.BookFlags.isReturnedBorrow(book);
    }

    /** 归还给借阅来源：把最新未归还的借书记录标记归还，状态变为电子书签 */
    private void confirmReturnToSource() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("归还给借阅来源")
                .setMessage("确认已把" + com.bai.bookshelf.util.BookText.quotedTitle(book.title)
                        + "归还给借阅来源？归还后藏书状态变为「电子书签」，不再显示存放地点与来源；"
                        + "之后再添加新的借书记录即可重新借阅。")
                .setPositiveButton("确认归还", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    // v5.8.16：归还只改一行 JSON + 状态，撤销就是把改动前的整行写回
                    final Book before = com.bai.bookshelf.util.BookSnapshot.copy(book);
                    app.io(() -> {
                        String time = StatusText.today() + " "
                                + new java.text.SimpleDateFormat("HH:mm",
                                java.util.Locale.getDefault()).format(new java.util.Date());
                        String returned = com.bai.bookshelf.util.BorrowSourceRecords
                                .returnLatest(book, time);
                        if (returned == null) {
                            app.ui(() -> Toast.makeText(this, "没有待归还的借书记录",
                                    Toast.LENGTH_SHORT).show());
                            return;
                        }
                        book.updatedAt = System.currentTimeMillis();
                        book.dirty = true;
                        app.db().bookDao().update(book);
                        app.ui(() -> {
                            com.bai.bookshelf.util.BorrowReminder.refresh(this);
                            reload();
                            com.bai.bookshelf.util.UndoBar.offer(
                                    findViewById(android.R.id.content),
                                    "已归还，记录归还时间 " + returned,
                                    before == null ? null : () -> app.io(() -> {
                                        app.db().bookDao().update(before);
                                        app.ui(() -> {
                                            com.bai.bookshelf.util.BorrowReminder
                                                    .refresh(this);
                                            reload();
                                        });
                                    }), null);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除书籍")
                .setMessage("确定把" + com.bai.bookshelf.util.BookText.quotedTitle(book.title)
                        + "从书架删除吗？删除后 8 秒内可在提示条上撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    final String title = com.bai.bookshelf.util.BookText.bareTitle(book.title);
                    app.io(() -> {
                        // v4.23：级联清理收敛到 BookCascade（备注图+副本+借出记录+封面文件）
                        // v5.8.16：先落库、8 秒内可撤销；文件延后到窗口结束再删
                        final com.bai.bookshelf.util.BookCascade.Deleted deleted =
                                com.bai.bookshelf.util.BookCascade.deleteRecoverable(app, book.id);
                        app.ui(() -> {
                            com.bai.bookshelf.util.UndoBar.offer(
                                    null,  // 本页正在关闭，回到书架由 UndoBar.attach 补弹
                                    "已删除《" + title + "》",
                                    () -> app.io(() -> {
                                        com.bai.bookshelf.util.BookCascade.restore(app, deleted);
                                        app.ui(() -> Toast.makeText(app,
                                                "已恢复《" + title + "》",
                                                Toast.LENGTH_SHORT).show());
                                    }),
                                    () -> app.io(() -> com.bai.bookshelf.util.BookCascade
                                            .deleteFiles(app, deleted.files)));
                            finish();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
