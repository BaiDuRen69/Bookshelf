package com.bai.bookshelf.ui.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.WishBook;
import com.bai.bookshelf.databinding.ActivityGlobalSearchBinding;
import com.bai.bookshelf.databinding.ItemSearchRowBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.MainActivity;
import com.bai.bookshelf.ui.detail.BookDetailActivity;
import com.bai.bookshelf.ui.detail.BorrowHistoryActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.ui.excerpt.ExcerptListActivity;
import com.bai.bookshelf.ui.location.CategoryActivity;
import com.bai.bookshelf.ui.location.LocationActivity;
import com.bai.bookshelf.ui.location.SourceChannelActivity;
import com.bai.bookshelf.ui.location.TagActivity;
import com.bai.bookshelf.ui.mine.AboutActivity;
import com.bai.bookshelf.ui.mine.ApiSettingsActivity;
import com.bai.bookshelf.ui.mine.IncompleteBooksActivity;
import com.bai.bookshelf.ui.mine.LibraryManageActivity;
import com.bai.bookshelf.ui.mine.SplitActivity;
import com.bai.bookshelf.ui.repair.DatabaseRepairActivity;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.ui.series.SeriesListActivity;
import com.bai.bookshelf.ui.stats.AuthorActivity;
import com.bai.bookshelf.ui.stats.CountryActivity;
import com.bai.bookshelf.ui.stats.PublisherActivity;
import com.bai.bookshelf.ui.stats.ReadingCalendarActivity;
import com.bai.bookshelf.ui.stats.ReadingStatsActivity;
import com.bai.bookshelf.ui.stats.StudyReportActivity;
import com.bai.bookshelf.ui.wish.WishListActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.GlobalSearch;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.SearchText;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局搜索页（v5.8.16）：一次输入同时找 藏书 / 摘抄 / 想读 / 功能入口。
 *
 * <p>书架搜索只管藏书列表；想读、各处设置入口此前没有统一入口。
 * 匹配逻辑在 {@link GlobalSearch}（纯数据、可单测），本页只负责输入、分组渲染与跳转。
 * 拼音 haystack 在进页时建一次并缓存到实例，边打字不再重复转写。
 */
public class GlobalSearchActivity extends BaseActivity {

    /** 从书架搜索框带进来的关键词 */
    public static final String EXTRA_QUERY = "global_query";

    private ActivityGlobalSearchBinding b;
    private final Handler debounce = new Handler(Looper.getMainLooper());
    private final Runnable pendingQuery = this::runQuery;

    private List<Book> books = new ArrayList<>();
    private List<Excerpt> excerpts = new ArrayList<>();
    private List<WishBook> wishes = new ArrayList<>();
    private Map<Long, String> index = new HashMap<>();
    private Map<Long, String> bookTitles = new HashMap<>();
    private boolean dataReady;
    /** 只让最新一次查询上屏（与书架筛选同样的代际号做法） */
    private long generation;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityGlobalSearchBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsHelper.applyTopInset(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> finish());

        String initial = getIntent().getStringExtra(EXTRA_QUERY);
        if (initial != null && !initial.trim().isEmpty()) {
            b.etQuery.setText(initial.trim());
        }
        b.etQuery.setSelection(b.etQuery.getText().length());
        b.etQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                debounce.removeCallbacks(pendingQuery);
                debounce.postDelayed(pendingQuery, 200);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        b.etQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                debounce.removeCallbacks(pendingQuery);
                runQuery();
                return true;
            }
            return false;
        });
        loadData();
        showKeyboard();
    }

    private void showKeyboard() {
        b.etQuery.post(() -> {
            b.etQuery.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(b.etQuery, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /** 进页先把三张表读出来并建好拼音索引（之后每次输入只做 contains） */
    private void loadData() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final List<Book> all = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
            final Map<Long, String> haystacks = new HashMap<>();
            final Map<Long, String> titles = new HashMap<>();
            for (Book book : all) {
                haystacks.put(book.id, SearchText.haystack(book));
                titles.put(book.id, book.title);
            }
            final List<Excerpt> allExcerpts = app.db().excerptDao().getAll();
            final List<WishBook> allWishes = app.db().wishBookDao().getAllManual();
            app.ui(() -> {
                books = all;
                index = haystacks;
                bookTitles = titles;
                excerpts = allExcerpts;
                wishes = allWishes;
                dataReady = true;
                runQuery();
            });
        });
    }

    private void runQuery() {
        if (!dataReady || b == null) {
            return;
        }
        final String keyword = b.etQuery.getText().toString().trim();
        final String norm = SearchText.normalize(keyword);
        final long gen = ++generation;
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final GlobalSearch.Result result =
                    GlobalSearch.query(norm, books, index, excerpts, bookTitles, wishes,
                            GlobalSearch.PER_GROUP);
            app.ui(() -> {
                if (gen != generation || b == null) {
                    return;
                }
                render(keyword, result);
            });
        });
    }

    private void render(String keyword, GlobalSearch.Result result) {
        b.tvHint.setVisibility(keyword.isEmpty() ? View.VISIBLE : View.GONE);
        b.tvEmpty.setVisibility(keyword.isEmpty() || !result.isEmpty() ? View.GONE : View.VISIBLE);
        fillGroup(b.groupBooks, b.llBooks, result.books, keyword,
                hit -> openBook(hit.id));
        fillGroup(b.groupExcerpts, b.llExcerpts, result.excerpts, keyword,
                hit -> openExcerpts(hit.id, hit.sub));
        fillGroup(b.groupWishes, b.llWishes, result.wishes, keyword,
                hit -> startActivity(new Intent(this, WishListActivity.class)));
        fillGroup(b.groupActions, b.llActions, result.actions, keyword,
                hit -> openTarget(hit.target));
    }

    private void fillGroup(View group, LinearLayout container, List<GlobalSearch.Hit> hits,
                           String keyword, RowClick onClick) {
        container.removeAllViews();
        if (hits.isEmpty()) {
            group.setVisibility(View.GONE);
            return;
        }
        group.setVisibility(View.VISIBLE);
        for (GlobalSearch.Hit hit : hits) {
            addRow(container, hit, keyword, () -> onClick.run(hit));
        }
    }

    private interface RowClick {
        void run(GlobalSearch.Hit hit);
    }

    private void addRow(LinearLayout container, GlobalSearch.Hit hit, String keyword,
                        Runnable onClick) {
        ItemSearchRowBinding row = ItemSearchRowBinding.inflate(
                getLayoutInflater(), container, false);
        int primary = ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY);
        int highlightBg = ThemeManager.primaryAlpha(this, 0x33);
        row.tvRowTitle.setText(SearchText.highlight(hit.title, keyword, primary, highlightBg));
        row.tvRowSub.setVisibility(hit.sub.isEmpty() ? View.GONE : View.VISIBLE);
        row.tvRowSub.setText(SearchText.highlight(hit.sub, keyword, primary, highlightBg));
        row.getRoot().setOnClickListener(v -> onClick.run());
        container.addView(row.getRoot());
    }

    private void openBook(long bookId) {
        Book book = null;
        for (Book candidate : books) {
            if (candidate.id == bookId) {
                book = candidate;
                break;
            }
        }
        if (book == null) {
            return;
        }
        Intent intent = new Intent(this, BookDetailActivity.class);
        intent.putExtra(BookDetailActivity.EXTRA_BOOK, book);
        startActivity(intent);
        finish();
    }

    /** 摘抄命中：跳到这本书的摘抄时间线 */
    private void openExcerpts(long bookId, String titleLine) {
        if (bookId <= 0) {
            return;
        }
        Intent intent = new Intent(this, ExcerptListActivity.class);
        intent.putExtra(ExcerptListActivity.EXTRA_BOOK_ID, bookId);
        intent.putExtra(ExcerptListActivity.EXTRA_BOOK_TITLE,
                BookText.bareTitle(bookTitles.getOrDefault(bookId, "")));
        startActivity(intent);
        finish();
    }

    /** 功能入口跳转；「自动备份 / 应用锁」这类开关在主页面里，就切到对应 Tab */
    private void openTarget(GlobalSearch.Target target) {
        if (target == null) {
            return;
        }
        switch (target) {
            case ADD_BOOK:
                startActivity(new Intent(this, BookEditActivity.class));
                break;
            case SCAN:
                startActivity(new Intent(this, ScanActivity.class));
                break;
            case PASTE_ISBN:
                startActivity(new Intent(this, com.bai.bookshelf.ui.scan.PasteIsbnActivity.class));
                break;
            case OCR:
                startActivity(new Intent(this, ScanActivity.class));
                break;
            case INVENTORY:
                Intent inventory = new Intent(this, ScanActivity.class);
                inventory.putExtra(ScanActivity.EXTRA_INVENTORY, true);
                startActivity(inventory);
                break;
            case WISH:
                startActivity(new Intent(this, WishListActivity.class));
                break;
            case SERIES:
                startActivity(new Intent(this, SeriesListActivity.class));
                break;
            case LOCATIONS:
                startActivity(new Intent(this, LocationActivity.class));
                break;
            case CHANNELS:
                startActivity(new Intent(this, SourceChannelActivity.class));
                break;
            case CATEGORIES:
                startActivity(new Intent(this, CategoryActivity.class));
                break;
            case TAGS:
                startActivity(new Intent(this, TagActivity.class));
                break;
            case AUTHORS:
                startActivity(new Intent(this, AuthorActivity.class));
                break;
            case PUBLISHERS:
                startActivity(new Intent(this, PublisherActivity.class));
                break;
            case COUNTRIES:
                startActivity(new Intent(this, CountryActivity.class));
                break;
            case READING_STATS:
                startActivity(new Intent(this, ReadingStatsActivity.class));
                break;
            case READING_CALENDAR:
                startActivity(new Intent(this, ReadingCalendarActivity.class));
                break;
            case YEAR_REPORT:
                startActivity(new Intent(this, StudyReportActivity.class));
                break;
            case SPLIT:
                startActivity(new Intent(this, SplitActivity.class));
                break;
            case INCOMPLETE:
                startActivity(new Intent(this, IncompleteBooksActivity.class));
                break;
            case LIBRARY_MANAGE:
                startActivity(new Intent(this, LibraryManageActivity.class));
                break;
            case BORROWERS:
                startActivity(new Intent(this,
                        com.bai.bookshelf.ui.lend.BorrowerActivity.class));
                break;
            case API_SETTINGS:
                startActivity(new Intent(this, ApiSettingsActivity.class));
                break;
            case ABOUT:
                startActivity(new Intent(this, AboutActivity.class));
                break;
            case REPAIR:
                startActivity(new Intent(this, DatabaseRepairActivity.class));
                break;
            case CHECK_IN:
                openMainTab(MainActivity.TAB_CHECKIN);
                break;
            default:
                // 自动备份 / 备份文件管理 / 应用锁 / 检查更新 / 导出 Excel 都在「我的」页
                // 四 Tab 之后「我的」是 3，不能再用写死的 2（那已经是打卡页）
                openMainTab(MainActivity.TAB_MINE);
                break;
        }
        finish();
    }

    private void openMainTab(int tab) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_TAB, tab);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}
