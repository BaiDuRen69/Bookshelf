package com.bai.bookshelf.ui.shelf;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.SheetAddBookBinding;
import com.bai.bookshelf.ui.AddBookHelper;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.ui.scan.ScanActivity;
import com.bai.bookshelf.util.InsetsHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

/**
 * 书架录入入口（v5.2，从 ShelfFragment 抽出）：
 * 全屏透明窗口 + 底部锚定的录入方式面板，以及手动输入 ISBN 弹窗。
 */
public final class ShelfAddActions {

    private ShelfAddActions() {
    }

    public static void showAddOptions(FragmentActivity activity,
                                      Consumer<Intent> launchAndRefresh) {
        SheetAddBookBinding sb = SheetAddBookBinding.inflate(activity.getLayoutInflater());

        // 全屏窗口 + 内部 FrameLayout：遮罩铺满，面板 gravity=bottom。
        // 不用 setLayout(宽度/重力) 钉浮动窗口：个别 ROM 会量成 WRAP 或贴错位置。
        Dialog sheet = new Dialog(activity);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(activity);
        View scrim = new View(activity);
        scrim.setBackgroundColor(0x73000000);
        scrim.setOnClickListener(v -> sheet.dismiss());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int bottomInset = InsetsHelper.systemBars(
                activity.getWindow().getDecorView()).bottom;
        int maxH = (int) (screenH * 0.88f);
        sb.getRoot().measure(
                View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int sheetH = Math.min(sb.getRoot().getMeasuredHeight(), maxH);
        sb.sheetContent.setPadding(
                sb.sheetContent.getPaddingLeft(), sb.sheetContent.getPaddingTop(),
                sb.sheetContent.getPaddingRight(),
                sb.sheetContent.getPaddingBottom() + bottomInset);
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sheetH, Gravity.BOTTOM);
        root.addView(sb.getRoot(), sheetLp);

        sheet.setContentView(root);
        Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(R.style.Animation_Bookshelf_Sheet);
            InsetsHelper.makeSheetWindowFullScreen(window);
        }

        sb.rowScan.setOnClickListener(v -> {
            sheet.dismiss();
            launchAndRefresh.accept(new Intent(activity, ScanActivity.class));
        });
        sb.rowIsbn.setOnClickListener(v -> {
            sheet.dismiss();
            showIsbnInput(activity);
        });
        sb.rowPaste.setOnClickListener(v -> {
            sheet.dismiss();
            launchAndRefresh.accept(new Intent(activity,
                    com.bai.bookshelf.ui.scan.PasteIsbnActivity.class));
        });
        sb.rowManual.setOnClickListener(v -> {
            sheet.dismiss();
            launchAndRefresh.accept(new Intent(activity, BookEditActivity.class));
        });

        sheet.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    /** 手动输入 ISBN 弹窗 */
    public static void showIsbnInput(FragmentActivity activity) {
        DialogInputBinding db = DialogInputBinding.inflate(activity.getLayoutInflater());
        EditText edit = db.edit;
        db.inputLayout.setHint("输入 10 或 13 位 ISBN");
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("按 ISBN 查询")
                .setView(db.getRoot())
                .setPositiveButton("查询", (d, w) -> {
                    String isbn = edit.getText().toString().trim();
                    if (isbn.length() < 10) {
                        Toast.makeText(activity, "ISBN 位数不对", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AddBookHelper.byIsbn(activity, isbn);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
