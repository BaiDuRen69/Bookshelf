package com.bai.bookshelf.ui.edit;

import android.content.Intent;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BookFormText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Map;

/**
 * 编辑页的保存流程（v5.6 从 BookEditActivity 抽出）：
 * 表单 → 对象、副本/备注图片快照、编辑或新建入库，以及重复 ISBN 的三选一处理。
 * 保存成功后的提示与收尾仍由宿主负责。
 */
public class BookSaveFlow {

    public interface Host {
        ActivityBookEditBinding b();

        Book book();

        boolean editMode();

        String categoryRaw();

        List<Location> locations();

        List<SourceChannel> channels();

        List<BookCopy> collectCopies();

        Map<Integer, List<NoteImage>> copyImages();

        List<NoteImage> noteImages();

        /** 保存前把阅读记录汇总到 book 的阅读状态/日期/评分 */
        void syncReadingRecords();

        /** 入库时把改过的轮次日集合写进 check_ins（在 io 线程调用） */
        void persistReadingDaySets(BookshelfApp app);

        /** 保存前把借书记录汇总到 book 的借阅日期与藏书状态 */
        void syncBorrowRecords();

        /** 保存完成：清理旧图并提示、结束页面 */
        void onSaved();

        /** 重复 ISBN 选择「增加一本副本」后的收尾 */
        void onCopyAdded(Book existing);
    }

    private final BaseActivity activity;
    private final Host host;

    public BookSaveFlow(BaseActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    /** 校验并保存到数据库 */
    public void save() {
        ActivityBookEditBinding b = host.b();
        String title = b.etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(activity, "书名不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        Book book = host.book();
        // 阅读记录在保存前统一汇总到书籍状态/日期/评分
        host.syncReadingRecords();
        // 表单 → 对象
        book.title = title;
        book.isbn = b.etIsbn.getText().toString().trim();
        book.author = b.etAuthor.getText().toString().trim();
        book.translator = b.etTranslator.getText().toString().trim();
        book.country = b.etCountry.getText().toString().trim();
        book.category = host.categoryRaw().trim();
        book.seriesName = b.etSeriesName.getText().toString().trim();
        book.seriesVolume = b.etSeriesVolume.getText().toString().trim();
        book.seriesOrder = parseInt(b.etSeriesOrder.getText().toString());
        if (book.seriesName.isEmpty()) {
            // 没有系列时卷号/阅读顺序没有意义，一并清掉，避免成为脏数据
            book.seriesVolume = "";
            book.seriesOrder = 0;
        }
        book.publisher = b.etPublisher.getText().toString().trim();
        book.publishDate = EditionFields.join(b.etPubYear, b.etPubMonth, b.etPubEdition); // 版次 yyyy-mm|N
        book.printing = EditionFields.join(b.etPrintYear, b.etPrintMonth, b.etPrintNo);   // 印次 yyyy-mm|N
        book.pages = parseInt(b.etPages.getText().toString());
        book.listPrice = parseDouble(b.etListPrice.getText().toString());
        book.wordCount = BookFormText.wordCountToStore(
                b.etWordCount.getText().toString()); // 统一存「X万字」
        book.doubanRating = b.etDouban.getText().toString().trim();
        // 标签由选择器直接维护在 book.tags，不从输入框回读
        book.coverUrl = b.etCoverUrl.getText().toString().trim();
        book.purchaseDate = b.etPurchaseDate.getText().toString().trim();
        book.purchasePrice = parseDouble(b.etPurchasePrice.getText().toString());
        // 拥有数量：留空或填 0 按 1 本处理
        book.quantity = Math.max(1, parseInt(b.etQuantity.getText().toString()));
        book.note = b.etNote.getText().toString().trim();
        book.stockStatus = b.spStock.getSelectedItemPosition();
        book.ownType = b.spOwn.getSelectedItemPosition();
        if (book.ownType == Book.OWN_BORROW) {
            // 多次借书记录：最后一次同步到汇总字段（开始/应还/归还）
            host.syncBorrowRecords();
        } else {
            // 切回自购 / 赠书后，借阅记录不再有意义（藏书状态沿用用户选择）
            book.borrowRecords = "";
            book.borrowStartDate = "";
            book.borrowDueDate = "";
            book.borrowReturnDate = "";
        }
        List<Location> locations = host.locations();
        int locPos = b.spLocation.getSelectedItemPosition();
        book.locationId = (locPos > 0 && locPos <= locations.size())
                ? locations.get(locPos - 1).id : null;
        if (book.ownType == Book.OWN_BORROW) {
            // 借阅来源取最近一次借书记录，供书架「来源」筛选与详情展示
            com.bai.bookshelf.util.BorrowSourceRecords.Record latest =
                    com.bai.bookshelf.util.BorrowSourceRecords.latest(book);
            book.sourceChannel = latest == null ? "" : latest.source.trim();
        } else {
            List<SourceChannel> channels = host.channels();
            int chPos = b.spSourceChannel.getSelectedItemPosition();
            book.sourceChannel = (chPos > 0 && chPos <= channels.size())
                    ? channels.get(chPos - 1).name : "";
        }

        long now = System.currentTimeMillis();
        book.updatedAt = now;
        book.dirty = true; // 二期云同步用

        // 副本栏目内容在 UI 线程收集（涉及视图读取），入库在后台线程
        final List<BookCopy> copiesToSave = host.collectCopies();
        final Map<Integer, List<NoteImage>> copyImagesToSave = host.copyImages();
        // 备注图片同样先取快照，避免后台线程读 UI 列表
        final List<NoteImage> imagesToSave = host.noteImages();

        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            if (host.editMode()) {
                BookEditSaver.saveEdit(app, book, copiesToSave, copyImagesToSave, imagesToSave);
                host.persistReadingDaySets(app);
                syncStartDayCheckIn(app, book);
            } else {
                // 新增前按 ISBN 查重（ISBN 为空则跳过）
                Book exist = book.isbn.isEmpty() ? null : app.db().bookDao().getByIsbn(book.isbn);
                if (exist != null) {
                    // v4.41：重复 ISBN 给三条路——改已有 / 加一本副本 / 仍然独立新建
                    final Book existing = exist;
                    app.ui(() -> new MaterialAlertDialogBuilder(activity)
                            .setTitle("书籍已录入")
                            .setMessage("《" + existing.title + "》已在书架中（共 "
                                    + existing.quantity + " 册），要怎么处理？")
                            .setPositiveButton("修改已有书籍", (d, w) -> {
                                Intent intent = new Intent(activity, BookEditActivity.class);
                                intent.putExtra(BookEditActivity.EXTRA_BOOK, existing);
                                activity.startActivity(intent);
                                activity.finish();
                            })
                            .setNeutralButton("增加副本", (d, w) ->
                                    app.io(() -> {
                                        BookEditSaver.addCopyToExisting(app, existing, book, now);
                                        app.ui(() -> host.onCopyAdded(existing));
                                    }))
                            .setNegativeButton("仍然新建", (d, w) ->
                                    app.io(() -> {
                                        BookEditSaver.insertNew(app, activity, book, copiesToSave,
                                                copyImagesToSave, imagesToSave, now);
                                        host.persistReadingDaySets(app);
                                        syncStartDayCheckIn(app, book);
                                        app.ui(host::onSaved);
                                    }))
                            .show());
                    return;
                }
                BookEditSaver.insertNew(app, activity, book, copiesToSave, copyImagesToSave,
                        imagesToSave, now);
                host.persistReadingDaySets(app);
                syncStartDayCheckIn(app, book);
            }
            app.ui(host::onSaved);
        });
    }

    /** Keep a round starting today and the check-in calendar in sync after save. */
    private static void syncStartDayCheckIn(BookshelfApp app, Book book) {
        com.bai.bookshelf.util.CheckInStore.ensureStartDayChecked(
                app.db(), book, com.bai.bookshelf.util.DateKeys.todayKey());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
