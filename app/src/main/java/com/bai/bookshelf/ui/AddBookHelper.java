package com.bai.bookshelf.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.bai.bookshelf.api.IsbnQueryService;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.ui.edit.BookEditActivity;

/**
 * 「按 ISBN 新增一本书」的统一流程（扫码页、手动输入共用）：
 * 本地查重 → 调 ISBN 接口补全 → 打开编辑页让用户确认入库。
 * 接口未配置或查询失败时，也会打开编辑页并预填 ISBN，转手动录入。
 */
public final class AddBookHelper {

    private AddBookHelper() {
    }

    public static void byIsbn(Activity activity, String isbn) {
        byIsbn(activity, isbn, null);
    }

    /**
     * @param onDuplicateResolved 命中重复时的收尾回调（无论用户选修改还是取消都会执行），
     *                            扫码页用它重置「处理中」标志与加载圈
     */
    public static void byIsbn(Activity activity, String isbn, Runnable onDuplicateResolved) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            Book exist = findDuplicate(app, isbn);
            app.ui(() -> {
                if (exist != null) {
                    // 已录入过：提示是否修改；对话框关闭后回调收尾（修复扫码页加载圈卡死）
                    new AlertDialog.Builder(activity)
                            .setTitle("书籍已录入")
                            .setMessage(com.bai.bookshelf.util.BookText.quotedTitle(exist.title)
                                    + "已在书架中，要修改它的信息吗？")
                            .setPositiveButton("修改", (d, w) -> {
                                Intent intent = new Intent(activity, BookEditActivity.class);
                                intent.putExtra(BookEditActivity.EXTRA_BOOK, exist);
                                activity.startActivity(intent);
                            })
                            .setNegativeButton("取消", null)
                            .setOnDismissListener(d -> {
                                if (onDuplicateResolved != null) {
                                    onDuplicateResolved.run();
                                }
                            })
                            .show();
                    return;
                }
                queryAndOpen(activity, isbn);
            });
        });
    }

    /** 调接口补全信息后打开编辑页 */
    private static void queryAndOpen(Activity activity, String isbn) {
        IsbnQueryService service = new IsbnQueryService(activity);

        // 未配置 API：直接转手动录入
        if (!service.isConfigured()) {
            Toast.makeText(activity, "未配置 ISBN 数据源，请手动录入（可在 我的→API 设置 中配置）", Toast.LENGTH_LONG).show();
            openEdit(activity, blankWithIsbn(isbn));
            return;
        }

        // 查询中转圈
        AlertDialog loading = new AlertDialog.Builder(activity)
                .setView(new ProgressBar(activity))
                .setCancelable(false)
                .create();
        loading.show();

        // Retrofit 的 enqueue 回调在主线程执行，无需再切线程
        service.query(isbn, (book, errorMsg) -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (book != null) {
                // v3.8 测试版：查到书后继续获取豆瓣评分（loading 保持转圈）；
                // 失败静默——评分留空、照常进编辑页，绝不阻塞入库主流程
                if (com.bai.bookshelf.api.DoubanService.enabled()) {
                    String title = book.title;
                    String author = book.author;
                    BookshelfApp.get().io(() -> {
                        String rating = com.bai.bookshelf.api.DoubanService
                                .fetchRatingSync(title, author);
                        BookshelfApp.get().ui(() -> {
                            loading.dismiss();
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                return;
                            }
                            if (rating != null) {
                                book.doubanRating = rating;
                                Toast.makeText(activity,
                                        "已自动获取豆瓣评分 " + rating, Toast.LENGTH_SHORT).show();
                            }
                            openEdit(activity, book);
                        });
                    });
                    return;
                }
                loading.dismiss();
                openEdit(activity, book);
            } else {
                loading.dismiss();
                Toast.makeText(activity, errorMsg, Toast.LENGTH_LONG).show();
                openEdit(activity, blankWithIsbn(isbn));
            }
        });
    }

    /**
     * 本地查重：先精确匹配 isbn；再用「去掉连字符/空格」的规范化形式兜底，
     * 避免同一本书因 978-7-111-54493-7 与 9787111544937 两种写法被录成两条。
     */
    private static Book findDuplicate(BookshelfApp app, String isbn) {
        Book exist = com.bai.bookshelf.util.BookSanitizer.normalize(app.db().bookDao().getByIsbn(isbn));
        if (exist != null) {
            return exist;
        }
        String key = normalize(isbn);
        if (key.length() < 10) {
            return null;
        }
        for (Book b : com.bai.bookshelf.util.BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
            if (normalize(b.isbn).equalsIgnoreCase(key)) {
                return b;
            }
        }
        return null;
    }

    /** ISBN 规范化：去掉连字符/空格等非书号字符，用于查重比较（库中仍存原始文本） */
    private static String normalize(String isbn) {
        return isbn == null ? "" : isbn.replaceAll("[^0-9Xx]", "");
    }

    private static Book blankWithIsbn(String isbn) {
        Book b = new Book();
        b.isbn = isbn;
        return b;
    }

    private static void openEdit(Activity activity, Book prefill) {
        Intent intent = new Intent(activity, BookEditActivity.class);
        intent.putExtra(BookEditActivity.EXTRA_PREFILL, prefill);
        activity.startActivity(intent);
    }
}
