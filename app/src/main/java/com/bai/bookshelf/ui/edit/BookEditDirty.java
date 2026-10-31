package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.util.BookFormText;
import com.bai.bookshelf.util.BookSanitizer;

/**
 * 编辑页的未保存检查（v5.6 从 BookEditActivity 抽出）：表单内容与初始值逐项比较。
 *
 * <p>老数据里 TEXT 列可能是 NULL（Room 的 String 字段在库里是可空列），
 * 一律用 nz 归一化后再比对，否则「NULL 对空串」会被误判成改动，
 * 出现「进编辑页什么都没改、返回却弹未保存确认框」的问题。
 */
public final class BookEditDirty {

    private BookEditDirty() {
    }

    /**
     * @param categoryRaw 分类栏的真实值（显示层是「一级-二级」，真实值存在 tag 里）
     */
    public static boolean check(ActivityBookEditBinding b, Book book, String initialTags,
                                EditPlaceChannels places, EditNoteImages noteImages,
                                EditCopiesSection copies, ReadingRecordSection readingRecords,
                                BorrowRecordSection borrowRecords, String categoryRaw) {
        // 文本类字段：展示侧已 trim，存储侧也 trim 再比对，避免首尾空格（如 Excel 导入带来的空格）被误判为改动
        if (!b.etTitle.getText().toString().trim().equals(nz(book.title).trim())) {
            return true;
        }
        if (!b.etIsbn.getText().toString().trim().equals(nz(book.isbn).trim())) {
            return true;
        }
        if (!b.etAuthor.getText().toString().trim().equals(nz(book.author).trim())) {
            return true;
        }
        if (!b.etTranslator.getText().toString().trim().equals(nz(book.translator).trim())) {
            return true;
        }
        if (!b.etCountry.getText().toString().trim().equals(nz(book.country).trim())) {
            return true;
        }
        // 分类栏显示「一级-二级」格式（v4.9），真实值在 tag，脏检查按真实值比对
        if (!categoryRaw.trim().equals(nz(book.category).trim())) {
            return true;
        }
        if (!b.etPublisher.getText().toString().trim().equals(nz(book.publisher).trim())) {
            return true;
        }
        // 版次/印次：三段拼回的值与「规整后的存储值」比对（v3.7——已存脏数据「2022-|」
        // 在回显时已拆成三段，未改动时拼回是「2022」，必须与自愈后的存储值比对，
        // 否则会被误判为改动而弹未保存提示）；自由文本（非标准串）兜底比对年框原文
        String pubJoined = EditionFields.join(b.etPubYear, b.etPubMonth, b.etPubEdition);
        if (!pubJoined.equals(BookSanitizer.sanitizeEdition(nz(book.publishDate)))
                && !b.etPubYear.getText().toString().trim().equals(nz(book.publishDate).trim())) {
            return true;
        }
        String printJoined = EditionFields.join(b.etPrintYear, b.etPrintMonth, b.etPrintNo);
        if (!printJoined.equals(BookSanitizer.sanitizeEdition(nz(book.printing)))
                && !b.etPrintYear.getText().toString().trim().equals(nz(book.printing).trim())) {
            return true;
        }
        // 字数：剥离「万字/万」后缀后再比对，避免「30万」与「30万字」被误判为改动
        if (!BookFormText.stripWanSuffix(b.etWordCount.getText().toString().trim())
                .equals(BookFormText.stripWanSuffix(nz(book.wordCount).trim()))) {
            return true;
        }
        if (!b.etDouban.getText().toString().trim().equals(nz(book.doubanRating).trim())) {
            return true;
        }
        if (!b.etSeriesName.getText().toString().trim().equals(nz(book.seriesName).trim())) {
            return true;
        }
        if (!b.etSeriesVolume.getText().toString().trim().equals(nz(book.seriesVolume).trim())) {
            return true;
        }
        if (parseInt(b.etSeriesOrder.getText().toString()) != book.seriesOrder) {
            return true;
        }
        if (!b.etCoverUrl.getText().toString().trim().equals(nz(book.coverUrl).trim())) {
            return true;
        }
        if (!b.etPurchaseDate.getText().toString().trim().equals(nz(book.purchaseDate).trim())) {
            return true;
        }
        if (!b.etNote.getText().toString().trim().equals(nz(book.note).trim())) {
            return true;
        }
        if (parseInt(b.etPages.getText().toString()) != book.pages) {
            return true;
        }
        if (Double.compare(parseDouble(b.etListPrice.getText().toString()), book.listPrice) != 0) {
            return true;
        }
        if (Double.compare(parseDouble(b.etPurchasePrice.getText().toString()),
                book.purchasePrice) != 0) {
            return true;
        }
        // 数量：bindFields 将 <=0 统一显示为 1，比对时也按此归一化，避免 quantity=0 被误判为改动
        int qtyDisplay = book.quantity <= 0 ? 1 : book.quantity;
        if (parseInt(b.etQuantity.getText().toString()) != qtyDisplay
                && !(b.etQuantity.getText().toString().trim().isEmpty() && qtyDisplay <= 1)) {
            return true;
        }
        if (!nz(book.tags).equals(nz(initialTags))) {
            return true;
        }
        if (noteImages.isDirty()) {
            return true; // 书级备注图片有增删
        }
        if (b.spStock.getSelectedItemPosition() != book.stockStatus) {
            return true;
        }
        if (b.spOwn.getSelectedItemPosition() != book.ownType) {
            return true;
        }
        // 异步列表加载完成前 initialLocationId/initialChannel 可能为 null，
        // 此时不应视为改动，避免刚进编辑页还没加载完就弹未保存框
        if (places.initialLocationId() != null
                && !java.util.Objects.equals(places.currentLocationId(),
                places.initialLocationId())) {
            return true;
        }
        if (places.initialChannel() != null
                && !java.util.Objects.equals(places.currentChannelName(),
                places.initialChannel())) {
            return true;
        }
        // 逐本信息或逐本备注图片有改动
        if (copies.isDirty()) {
            return true;
        }
        return readingRecords.isDirty() || borrowRecords.isDirty();
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

    /** null 安全：Room 的 String 字段在库里是可空列，老数据可能为 NULL，统一按空串处理 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
