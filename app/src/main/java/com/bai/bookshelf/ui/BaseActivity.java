package com.bai.bookshelf.ui;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 所有 Activity 的基类（v2.5 主题换肤）：
 * 在 super.onCreate 之前按用户选择 setTheme，保证 setContentView 及其后
 * inflate 的所有视图（含 Fragment）都跑在选定主题上。
 *
 * <p>v4.18：统一处理系统栏 inset。本应用 targetSdk = 36，在 Android 15+
 * 强制 edge-to-edge，内容会画到状态栏/导航栏之下；而顶部的 MaterialToolbar
 * 只有在自身被标记为 fitsSystemWindows 时才会自动避让。为避免逐页遗漏，
 * 这里在 onContentChanged 阶段统一给带 {@code @+id/toolbar} 的顶部栏
 * 加状态栏内边距。
 */
public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
    }

    /**
     * v4.54：应用锁统一拦截。所有业务页面都继承本类，因此无论从最近任务、
     * 通知还是应用内跳转进入，都会先被锁屏页挡在前面。
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (this instanceof com.bai.bookshelf.ui.lock.AppLockActivity) {
            return;
        }
        com.bai.bookshelf.util.AppLock.beforeActivityStart(this);
        if (com.bai.bookshelf.util.AppLock.isLocked(this)) {
            android.content.Intent intent = new android.content.Intent(this,
                    com.bai.bookshelf.ui.lock.AppLockActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        applyToolbarInset();
    }

    /**
     * 给页面顶部栏加状态栏内边距，使标题不被状态栏遮挡。
     *
     * <p>v4.19 修复：原先只在 {@code android.R.id.content} 的<b>直接子层</b>里找
     * {@code R.id.toolbar}。但 content 的直接子层其实是 Activity 的根布局
     * （如 activity_book_edit.xml 的 LinearLayout），工具栏是它的<b>孙节点</b>，
     * 于是二级页的工具栏从来没被处理过——标题直接压在状态栏底下（实测
     * 「录入书籍」标题 y=32~94px，状态栏 0~63px，完全重叠）。
     *
     * <p>现在改为在 Activity 根布局里递归查找。
     * {@link InsetsHelper#applyTopInset(View)} 是幂等的（用 tag 记住原始内边距），
     * 即使 Fragment 自己也调过一次，重复调用也不会让内边距层层累加。
     */
    private void applyToolbarInset() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        View toolbar = InsetsHelper.findById(root, R.id.toolbar);
        if (toolbar != null) {
            InsetsHelper.applyTopInset(toolbar);
        }
    }
}
