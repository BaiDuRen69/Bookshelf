package com.bai.bookshelf.ui.stats;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityStudyReportBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.InsetsHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 书房年报（v4.32）：把藏书拼成一张可分享的卡片。
 *
 * <p>数据全部本地计算：总册数、已读/在读、最爱作者、最贵/最厚，最近 4 本封面做拼贴；
 * 「分享这张卡片」用 View.draw 截成 PNG，经 FileProvider 发 ACTION_SEND。
 */
public class StudyReportActivity extends BaseActivity {

    private ActivityStudyReportBinding b;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityStudyReportBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsHelper.applyTopInset(b.toolbar);
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.toolbar.setTitle("书房年报");

        b.btnShareReport.setOnClickListener(v -> shareCard(b.cardReport));

        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> books = app.db().bookDao().getAll();
            int readDone = 0;
            int reading = 0;
            Map<String, Integer> authorCount = new HashMap<>();
            Book mostExpensive = null;
            Book thickest = null;
            for (Book bk : books) {
                if (bk.readStatus == 2) {
                    readDone++;
                } else if (bk.readStatus == 1) {
                    reading++;
                }
                String author = bk.author == null ? "" : bk.author.trim();
                if (!author.isEmpty()) {
                    authorCount.put(author, authorCount.getOrDefault(author, 0) + 1);
                }
                if (mostExpensive == null || bk.listPrice > mostExpensive.listPrice) {
                    mostExpensive = bk;
                }
                if (thickest == null || bk.pages > thickest.pages) {
                    thickest = bk;
                }
            }
            String topAuthor = "—";
            int topCount = 0;
            for (Map.Entry<String, Integer> e : authorCount.entrySet()) {
                if (e.getValue() > topCount) {
                    topCount = e.getValue();
                    topAuthor = e.getKey();
                }
            }
            List<Book> recent = new ArrayList<>(books);
            recent.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));
            if (recent.size() > 4) {
                recent = recent.subList(0, 4);
            }
            final int fReadDone = readDone;
            final int fReading = reading;
            final int fTotal = books.size();
            final String fAuthor = topAuthor;
            final int fTopCount = topCount;
            final Book fExpensive = mostExpensive;
            final Book fThickest = thickest;
            final List<Book> fRecent = recent;
            app.ui(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                bind(fTotal, fReadDone, fReading, fAuthor, fTopCount,
                        fExpensive, fThickest, fRecent);
            });
        });
    }

    private void bind(int total, int readDone, int reading, String author, int authorCount,
                      Book mostExpensive, Book thickest, List<Book> recent) {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        b.tvReportTitle.setText("我的书房 · " + year);
        b.tvReportTotal.setText(total + " 册");
        b.tvReportRead.setText("已读 " + readDone + " 本 · 在读 " + reading + " 本");
        b.tvReportAuthor.setText(authorCount > 0
                ? "最爱作者：" + author + "（" + authorCount + " 本）"
                : "最爱作者：—");
        b.tvReportPrice.setText(mostExpensive == null || mostExpensive.listPrice <= 0
                ? "最贵的一本：—"
                : String.format(Locale.getDefault(), "最贵的一本：《%s》 ¥%.2f",
                        safeTitle(mostExpensive), mostExpensive.listPrice));
        b.tvReportPages.setText(thickest == null || thickest.pages <= 0
                ? "最厚的一本：—"
                : "最厚的一本：《" + safeTitle(thickest) + "》 " + thickest.pages + " 页");

        ImageView[] views = {b.ivReport1, b.ivReport2, b.ivReport3, b.ivReport4};
        for (int i = 0; i < views.length; i++) {
            if (i >= recent.size()) {
                views[i].setVisibility(View.GONE);
                continue;
            }
            views[i].setVisibility(View.VISIBLE);
            com.bai.bookshelf.util.CoverLoader.load(views[i], recent.get(i));
        }
    }

    private String safeTitle(Book book) {
        String title = com.bai.bookshelf.util.BookText.displayTitle(book.title);
        return title.replace("《", "").replace("》", "");
    }

    /** 把卡片截成 PNG 并分享（v4.32） */
    private void shareCard(View card) {
        if (card.getWidth() <= 0 || card.getHeight() <= 0) {
            return;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(card.getWidth(), card.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            card.draw(canvas);

            File dir = new File(getFilesDir(), "share");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, "study_report_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, "我的书房年报 · 小书房");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享书房年报"));
            b.btnShareReport.performHapticFeedback(
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "分享失败：" + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
