package com.bai.bookshelf.ui.scan;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 扫码页「待录入」底部面板的视图树（v5.6 从 ScanBatchController 抽出）。
 * 只负责搭界面与尺寸/动画；列表内容、地点选择与保存逻辑由控制器接管。
 */
final class BatchSheetView {

    final Dialog dialog;
    final LinearLayout list;
    final TextView saveBtn;
    final TextView locationRow;
    final CheckBox copyCheck;

    private BatchSheetView(Dialog dialog, LinearLayout list, TextView saveBtn,
                           TextView locationRow, CheckBox copyCheck) {
        this.dialog = dialog;
        this.list = list;
        this.saveBtn = saveBtn;
        this.locationRow = locationRow;
        this.copyCheck = copyCheck;
    }

    static BatchSheetView show(BaseActivity activity, String locationName) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(activity);
        View scrim = new View(activity);
        scrim.setBackgroundColor(0x73000000);
        scrim.setOnClickListener(v -> dialog.dismiss());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int bottomInset = InsetsHelper.systemBars(activity.getWindow().getDecorView()).bottom;

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundResource(R.drawable.bg_bottom_sheet);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        content.setPadding(pad, dp(activity, 8), pad, dp(activity, 12) + bottomInset);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        View handle = new View(activity);
        handle.setBackgroundResource(R.drawable.bg_sheet_handle);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                dp(activity, 36), dp(activity, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(activity, 4);
        hlp.bottomMargin = dp(activity, 10);
        content.addView(handle, hlp);

        TextView title = new TextView(activity);
        title.setText("待录入");
        title.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                activity.getResources().getDimension(R.dimen.text_title));
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        content.addView(title);

        TextView locationRow = new TextView(activity);
        locationRow.setBackgroundResource(R.drawable.bg_dialog_row);
        locationRow.setPadding(dp(activity, 14), dp(activity, 12),
                dp(activity, 14), dp(activity, 12));
        locationRow.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface));
        locationRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        locationRow.setText("存放地点：" + locationName + "（点按更换）");
        locationRow.setClickable(true);
        locationRow.setFocusable(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(activity, 10);
        content.addView(locationRow, llp);

        ScrollView listScroll = new ScrollView(activity);
        listScroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenH * 0.42f));
        listLp.topMargin = dp(activity, 6);
        content.addView(listScroll, listLp);

        CheckBox copyCheck = new CheckBox(activity);
        copyCheck.setText("重复的书也各加 1 册副本");
        copyCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        copyCheck.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface_variant));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(activity, 8);
        content.addView(copyCheck, clp);

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
        flp.topMargin = dp(activity, 10);
        content.addView(footer, flp);

        TextView keepScanning = new TextView(activity);
        keepScanning.setText("继续扫描");
        keepScanning.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface_variant));
        keepScanning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        keepScanning.setGravity(Gravity.CENTER);
        keepScanning.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        keepScanning.setClickable(true);
        keepScanning.setOnClickListener(v -> dialog.dismiss());
        footer.addView(keepScanning, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        footer.addView(new View(activity),
                new LinearLayout.LayoutParams(0, 1, 1f));

        TextView saveBtn = new TextView(activity);
        saveBtn.setText("保存");
        saveBtn.setTextColor(ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY));
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        saveBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        saveBtn.setClickable(true);
        footer.addView(saveBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        scroll.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((int) (screenH * 0.9f),
                        View.MeasureSpec.AT_MOST));
        int sheetH = Math.min(scroll.getMeasuredHeight(), (int) (screenH * 0.9f));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sheetH, Gravity.BOTTOM));

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(R.style.Animation_Bookshelf_Sheet);
            InsetsHelper.makeSheetWindowFullScreen(window);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return new BatchSheetView(dialog, list, saveBtn, locationRow, copyCheck);
    }

    private static int dp(BaseActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
