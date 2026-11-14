package com.bai.bookshelf.ui.debug;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bai.bookshelf.util.ThemeManager;

/**
 * 仅 debug：仪器化测试用的最小宿主 Activity，只要一个真 Activity 来挂 Fragment、收返回键。
 *
 * <p>刻意不继承 {@code BaseActivity}——那条链上有应用锁拦截与主题 recreate，
 * 仓库里两处测试注释都记过「启动 Activity 会让测试挂起」。主题仍在 super.onCreate
 * 之前套好，与 BaseActivity 同一写法，否则 AppCompat 会拒绝非 AppCompat 主题。
 *
 * <p>必须放在 debug 源集而不是 androidTest：androidTest 的包名带 {@code .test} 后缀，
 * 声明在那边的 Activity 属于另一个进程，{@code Instrumentation.startActivitySync}
 * 会以「resolved to different process」拒绝启动。
 */
public class CheckInBackHostActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        // 屏幕一灭，宿主就走 onSaveInstanceState，随后 commitNow 抛
        // "Can not perform this action after onSaveInstanceState"。用窗口标志保持常亮，
        // 不动 stay_on_while_plugged_in 等系统设置（标志随窗口销毁，无需还原）。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
}