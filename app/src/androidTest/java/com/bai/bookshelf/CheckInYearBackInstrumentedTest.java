package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.ui.checkin.CheckInFragment;
import com.bai.bookshelf.ui.checkin.CheckInViewSwitcher;
import com.bai.bookshelf.ui.debug.CheckInBackHostActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * EP-I5 · T13 / T14：打卡页月↔年转场在**真 Activity、真布局、真转场派发**下的两条验收。
 *
 * <p>这是仓库里唯一真启动 Activity 的测试文件，宿主用 debug 源集自己声明的
 * {@link CheckInBackHostActivity}（不继承 BaseActivity，绕开应用锁 / splash / recreate）。
 * 返回键走 {@code ComponentActivity.onBackPressed()}——它与 KEYCODE_BACK 落到的是
 * 同一条 OnBackPressedDispatcher。
 *
 * <p>**不用 {@code ActivityScenario.launch}**：它从应用 uid 起 Activity，本机 MIUI 会拦
 * （{@code ActivityStarterImpl: MIUILOG- Permission Denied Activity}，或弹
 * CHECK_ALLOW_START_ACTIVITY 确认框），而且 {@code launch} 没有超时会永久挂住。
 * 这里改成 {@code UiAutomation.executeShellCommand("am start …")}——那条命令以 **shell uid**
 * 执行，起 exported 组件是稳的（宿主因此在 debug 清单里声明 exported=true，不进 release）。
 * 等 RESUMED 用带超时的 latch，起不来就明确报错，不会静默挂死。
 *
 * <p>宿主自带 {@code FLAG_KEEP_SCREEN_ON}：屏幕一灭就走 onSaveInstanceState，
 * 随后 {@code commitNow} 抛 "Can not perform this action after onSaveInstanceState"。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInYearBackInstrumentedTest {

    private static final String HOST =
            "com.bai.bookshelf.devtest/com.bai.bookshelf.ui.debug.CheckInBackHostActivity";

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }

    /** UiAutomation 的 shell 命令以 shell uid 执行，不是应用 uid——这是绕开 MIUI 拦截的关键 */
    private static String shell(String cmd) throws Exception {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(cmd);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toString("UTF-8");
    }

    private static CheckInBackHostActivity launchHost() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        final CountDownLatch resumed = new CountDownLatch(1);
        final CheckInBackHostActivity[] holder = new CheckInBackHostActivity[1];
        Application.ActivityLifecycleCallbacks cbs = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) {
                if (a instanceof CheckInBackHostActivity) {
                    holder[0] = (CheckInBackHostActivity) a;
                    resumed.countDown();
                }
            }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        };
        app.registerActivityLifecycleCallbacks(cbs);
        try {
            // NEW_TASK | CLEAR_TASK：每次都拿一个全新宿主，不复用上一条测试正在 finish 的实例
            String out = shell("am start -f 0x10008000 -n " + HOST);
            assertTrue("20s 内没等到宿主 RESUMED；am start 输出=" + out,
                    resumed.await(20, TimeUnit.SECONDS));
        } finally {
            app.unregisterActivityLifecycleCallbacks(cbs);
        }
        return holder[0];
    }

    /** 挂上打卡页并等宿主真布局完；不 laid out 的话 beginDelayedTransition 直接 return，测的就不是真路径 */
    private static CheckInFragment attach(AppCompatActivity a) throws Exception {
        final CheckInFragment[] holder = new CheckInFragment[1];
        onMain(() -> {
            CheckInFragment f = new CheckInFragment();
            holder[0] = f;
            a.getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, f, "checkin").commitNow();
        });
        CheckInFragment f = holder[0];
        View host = f.getView().findViewById(R.id.view_switch_host);
        long deadline = System.currentTimeMillis() + 5000;
        while (!host.isLaidOut() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("宿主要先 laid out", host.isLaidOut());
        return f;
    }

    /** 三个状态都在主线程取，避免跨线程读到「yearVisible 翻了、回调还没同步」的撕裂瞬间 */
    private static boolean[] sample(CheckInViewSwitcher sw, OnBackPressedCallback cb) {
        final boolean[] out = new boolean[3];
        onMain(() -> {
            out[0] = sw.isAnimating();
            out[1] = sw.isYearVisible();
            out[2] = cb.isEnabled();
        });
        return out;
    }

    private static void settle(CheckInViewSwitcher sw, OnBackPressedCallback cb)
            throws InterruptedException {
        long deadline = System.currentTimeMillis()
                + CheckInViewSwitcher.ANIM_TIMEOUT_MS + CheckInViewSwitcher.DURATION_MS + 2000;
        while (sample(sw, cb)[0] && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse("转场必须在有限时间内沉降", sample(sw, cb)[0]);
    }

    private static void report(String line) {
        Bundle b = new Bundle();
        b.putString("t14", line);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, b);
    }

    @Test
    public void 年视图按返回回月视图且不退出Activity() throws Exception {
        AppCompatActivity a = launchHost();
        try {
            CheckInFragment f = attach(a);
            View root = f.getView();
            assertNotNull(root);
            CheckInViewSwitcher sw = (CheckInViewSwitcher) field(f, "switcher");
            OnBackPressedCallback cb = (OnBackPressedCallback) field(f, "yearBackCallback");
            assertNotNull(sw);
            assertNotNull("onViewCreated 必须注册返回回调", cb);

            // ① 月视图：回调不拦返回
            boolean[] start = sample(sw, cb);
            assertFalse(start[1]);
            assertFalse(start[2]);

            // ② 进年视图走**真标题栏点击**，不是直接调 switcher：syncYearBack 归 Fragment 管，
            //    直接 sw.toYear(pivot, null) 会绕开它，测的就不是用户能走到的路径。
            //    月→年经 openYearView 的 app.io 异步取数（CheckInFragment:414-435），要等它真翻。
            View toolbar = root.findViewById(R.id.toolbar);
            onMain(toolbar::performClick);
            long deadline = System.currentTimeMillis() + 3000;
            while (!sample(sw, cb)[1] && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            boolean[] entered = sample(sw, cb);
            assertTrue("点标题栏必须能进年视图", entered[1]);
            // yearVisible 与回调在同一个主线程消息里翻（:431 toYear → :433 syncYearBack），
            // 中间插不进返回键派发，所以「转场一开始就拦住」等价于这一刻两者同真
            assertTrue("转场一开始就要拦住返回", entered[2]);
            settle(sw, cb);

            // ③ 年视图按返回：回月视图，Activity 不许 finishing
            final boolean[] finishing = new boolean[1];
            onMain(() -> {
                a.onBackPressed();
                finishing[0] = a.isFinishing();
            });
            assertFalse("年视图的返回必须被吃掉，不能退出 Activity", finishing[0]);
            boolean[] afterBack = sample(sw, cb);
            assertFalse(afterBack[1]);
            assertFalse("回到月视图后回调要立刻放开", afterBack[2]);

            settle(sw, cb);
            assertEquals(View.VISIBLE, root.findViewById(R.id.month_content).getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.year_content).getVisibility());

            // ④ 反向对照：月视图的返回必须照常退出，不能被永久吃掉。
            //    框架走的是 finishAfterTransition()，isFinishing() 不会在同一刻就为真，
            //    所以要带超时地等，而不是紧接着断言。
            onMain(a::onBackPressed);
            long finishDeadline = System.currentTimeMillis() + 3000;
            final boolean[] finishingNow = new boolean[1];
            while (System.currentTimeMillis() < finishDeadline) {
                onMain(() -> finishingNow[0] = a.isFinishing());
                if (finishingNow[0]) {
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue("月视图的返回不该被拦", finishingNow[0]);
        } finally {
            onMain(a::finish);
        }
    }

    /**
     * EP-I5 · T14 步骤③：按人手节奏连点 20 次标题栏（月↔年），逐轮记录「是否可点 / 返回是否可用」。
     *
     * <p>点的是真 {@code toolbar.performClick()}，走的就是 {@code CheckInFragment:238} 那条
     * OnClickListener → {@code toggleYearView()} 的 {@code isAnimating()} 守卫，与手指点击同一条路径。
     * 间隔在规格给的 300–500ms 里循环取值。
     *
     * <p>「是否可点」直接读守卫条件本身：{@code toggleYearView()} 只在 {@code isAnimating()} 为真时
     * return，所以**点前 animating==true 就等于这一次被挡掉**。不能拿「yearVisible有没有翻」当判据——
     * 月→年走 {@code openYearView()} 的 {@code app.io} 异步取数，{@code toYear} 要等取数回调才跑；
     * 年→月才是同步的。
     *
     * <p>300ms 恰好等于 {@code DURATION_MS}，这一档**本来就该被守卫挡掉**，所以不断言「20 次全生效」。
     * 断言的是**不许永久卡死**：返回回调在每一次采样都必须跟着 yearVisible、连点结束后 animating
     * 必须在有限时间内复位、复位后标题栏还点得动、年视图按返回仍能回月视图且不退出 Activity。
     */
    @Test
    public void 人手节奏连点20次年月切换不卡死() throws Exception {
        final int[] gaps = {300, 350, 400, 450, 500};
        AppCompatActivity a = launchHost();
        try {
            CheckInFragment f = attach(a);
            View root = f.getView();
            View toolbar = root.findViewById(R.id.toolbar);
            CheckInViewSwitcher sw = (CheckInViewSwitcher) field(f, "switcher");
            OnBackPressedCallback cb = (OnBackPressedCallback) field(f, "yearBackCallback");

            boolean[] start = sample(sw, cb);
            assertFalse("起点必须是月视图", start[1]);
            assertFalse("起点不该在动画中", start[0]);

            int accepted = 0;
            int rejected = 0;
            for (int i = 1; i <= 20; i++) {
                final boolean[] blocked = new boolean[1];
                onMain(() -> {
                    blocked[0] = sw.isAnimating();
                    toolbar.performClick();
                });
                if (blocked[0]) {
                    rejected++;
                } else {
                    accepted++;
                }
                boolean[] atClick = sample(sw, cb);
                assertEquals("第" + i + "轮：点击当刻返回拦截就要跟着年可见",
                        atClick[1], atClick[2]);

                int gap = gaps[(i - 1) % gaps.length];
                Thread.sleep(gap);
                boolean[] mid = sample(sw, cb);
                assertEquals("第" + i + "轮：返回拦截必须始终跟着年可见", mid[1], mid[2]);
                report(String.format(Locale.US,
                        "第%02d轮 间隔%dms 可点=%s 点前animating=%s 隔末年可见=%s 返回拦截=%s 隔末animating=%s",
                        i, gap, blocked[0] ? "否" : "是", blocked[0], mid[1], mid[2], mid[0]));
            }

            report("20 轮合计：可点 " + accepted + " 次，被守卫挡掉 " + rejected + " 次");
            assertEquals(20, accepted + rejected);

            // 连点结束后必须在有限时间内沉降——这条才是「不许永久卡死」的正面判据
            settle(sw, cb);
            boolean[] settled = sample(sw, cb);
            assertEquals("沉降后返回拦截仍须跟着年可见", settled[1], settled[2]);

            // 沉降后还必须点得动（月→年是异步的，等它真翻）
            final boolean[] wasYear = new boolean[1];
            onMain(() -> {
                wasYear[0] = sw.isYearVisible();
                toolbar.performClick();
            });
            long flipDeadline = System.currentTimeMillis() + 3000;
            while (sample(sw, cb)[1] == wasYear[0] && System.currentTimeMillis() < flipDeadline) {
                Thread.sleep(50);
            }
            assertNotEquals("20 轮后标题栏还必须点得动", wasYear[0], sample(sw, cb)[1]);
            settle(sw, cb);

            // 停在年视图就按返回验一次；停在月视图就先点进年视图
            if (!sample(sw, cb)[1]) {
                onMain(toolbar::performClick);
                long d = System.currentTimeMillis() + 3000;
                while (!sample(sw, cb)[1] && System.currentTimeMillis() < d) {
                    Thread.sleep(50);
                }
                settle(sw, cb);
            }
            assertTrue(sample(sw, cb)[1]);
            assertTrue(cb.isEnabled());
            final boolean[] finishing = new boolean[1];
            onMain(() -> {
                a.onBackPressed();
                finishing[0] = a.isFinishing();
            });
            assertFalse("连点 20 次后年视图的返回仍须被吃掉", finishing[0]);
            assertFalse(sample(sw, cb)[1]);
            settle(sw, cb);
            assertEquals(View.VISIBLE, root.findViewById(R.id.month_content).getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.year_content).getVisibility());
        } finally {
            onMain(a::finish);
        }
    }
}