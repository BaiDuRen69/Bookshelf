package com.bai.bookshelf.ui.edit;

import android.content.Context;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.NoteImage;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 编辑页持久化（v5.7，从 BookEditActivity 抽出）：
 * 保存已有书 / 新建 / 加副本，以及副本与备注图片的整批重写。
 */
public final class BookEditSaver {

    private BookEditSaver() {
    }

    /** 编辑已有书：更新主记录 + 重写副本 + 书级备注图片 */
    public static void saveEdit(BookshelfApp app, Book book,
                                List<BookCopy> copies,
                                Map<Integer, List<NoteImage>> copyImages,
                                List<NoteImage> images) {
        app.db().bookDao().update(book);
        saveCopies(app, book.id, copies, copyImages);
        saveNoteImages(app, bookNoteKey(book.id), images);
    }

    /** 新建一本：写 books / 副本 / 备注图片，并记住本次地点 */
    public static void insertNew(BookshelfApp app, Context ctx, Book book,
                                 List<BookCopy> copies,
                                 Map<Integer, List<NoteImage>> copyImages,
                                 List<NoteImage> images, long now) {
        book.createdAt = now;
        book.id = app.db().bookDao().insert(book);
        saveCopies(app, book.id, copies, copyImages);
        if (book.id > 0) {
            saveNoteImages(app, bookNoteKey(book.id), images);
        }
        ctx.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).edit()
                .putLong("last_location_id", book.locationId == null ? -1 : book.locationId)
                .apply();
    }

    /** 已存在的书再加一本：数量 +1，并把本次表单填写的地点/购入信息挂到新副本上 */
    public static void addCopyToExisting(BookshelfApp app, Book exist, Book draft, long now) {
        exist.quantity = exist.quantity + 1;
        exist.updatedAt = now;
        exist.dirty = true;
        app.db().bookDao().update(exist);
        BookCopy copy = new BookCopy();
        copy.bookId = exist.id;
        copy.copyIndex = exist.quantity;
        copy.stockStatus = Book.STOCK_IN;
        copy.locationId = draft.locationId;
        copy.ownType = draft.ownType;
        copy.sourceChannel = draft.sourceChannel;
        copy.purchaseDate = draft.purchaseDate;
        copy.purchasePrice = draft.purchasePrice;
        app.db().bookCopyDao().insert(copy);
    }

    /** 副本整批重写；同时回收被删副本的备注图片记录与文件 */
    private static void saveCopies(BookshelfApp app, long bookId, List<BookCopy> copies,
                                   Map<Integer, List<NoteImage>> copyImages) {
        List<NoteImage> oldCopyImgs = app.db().noteImageDao()
                .getByOwnerKeyPrefix("copy:" + bookId + ":%");

        app.db().bookCopyDao().deleteByBook(bookId);
        for (BookCopy copy : copies) {
            copy.bookId = bookId;
        }
        if (!copies.isEmpty()) {
            app.db().bookCopyDao().insertAll(copies);
        }

        int kept = copies.size();
        for (NoteImage img : oldCopyImgs) {
            int index = parseCopyIndex(img.ownerKey, bookId);
            if (index >= kept) {
                app.db().noteImageDao().deleteById(img.id);
                if (img.path != null && !img.path.isEmpty()) {
                    new File(img.path).delete();
                }
            }
        }

        if (copyImages != null) {
            for (Map.Entry<Integer, List<NoteImage>> entry : copyImages.entrySet()) {
                saveNoteImages(app, "copy:" + bookId + ":" + entry.getKey(), entry.getValue());
            }
        }
    }

    /** 备注图片整批重写：先删该 ownerKey 旧行，再插当前列表 */
    private static void saveNoteImages(BookshelfApp app, String ownerKey,
                                       List<NoteImage> images) {
        app.db().noteImageDao().deleteByOwnerKey(ownerKey);
        if (!images.isEmpty()) {
            for (int i = 0; i < images.size(); i++) {
                NoteImage image = images.get(i);
                image.ownerKey = ownerKey;
                image.sort = i;
            }
            app.db().noteImageDao().insertAll(images);
        }
    }

    /** 从 ownerKey「copy:{bookId}:{index}」解析副本下标；格式不符视为要删 */
    private static int parseCopyIndex(String ownerKey, long bookId) {
        if (ownerKey == null) {
            return Integer.MAX_VALUE;
        }
        String prefix = "copy:" + bookId + ":";
        if (!ownerKey.startsWith(prefix)) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(ownerKey.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String bookNoteKey(long bookId) {
        return "book:" + bookId;
    }
}
