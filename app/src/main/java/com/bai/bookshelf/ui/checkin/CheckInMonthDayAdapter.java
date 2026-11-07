package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ItemCheckinMonthDayBinding;
import com.bai.bookshelf.util.CheckInDayDetail;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 当月逐日明细（5.5）：整月每天一行，今天贴淡底、未来日退后。
 *
 * <p>行只负责展示与「点某天回到书目列表」；数据由
 * {@link CheckInDayDetail#ofMonth} 统一组装（0 条的日期也保留）。
 */
public class CheckInMonthDayAdapter extends RecyclerView.Adapter<CheckInMonthDayAdapter.VH> {

    public interface Listener {
        void onDayClick(CheckInDayDetail.Day day);
    }

    private static final String[] WEEKDAYS =
            {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    private final List<CheckInDayDetail.Day> days = new ArrayList<>();
    private int todayKey;
    @Nullable
    private Listener listener;

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    public void submit(List<CheckInDayDetail.Day> data, int todayKey) {
        days.clear();
        if (data != null) {
            days.addAll(data);
        }
        this.todayKey = todayKey;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemCheckinMonthDayBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CheckInDayDetail.Day day = days.get(position);
        String weekday = weekdayOf(day.dayKey);
        holder.b.tvDay.setText(String.valueOf(day.dayKey % 100));
        holder.b.tvWeekday.setText(weekday);
        String state = day.total > 0
                ? "已打卡 " + day.total + " 本" + (day.backfill > 0 ? " · 补 " + day.backfill : "")
                : "未打卡";
        holder.b.tvState.setText(state);
        String titles = day.titles.isEmpty() ? "" : TextUtils.join("、", day.titles);
        holder.b.tvTitles.setText(titles);
        holder.b.tvTitles.setVisibility(titles.isEmpty() ? View.GONE : View.VISIBLE);
        holder.itemView.setBackground(day.dayKey == todayKey
                ? todayBackground(holder.itemView.getContext()) : null);
        holder.itemView.setAlpha(day.future ? 0.6f : 1f);
        holder.itemView.setContentDescription(monthDay(day.dayKey) + "，" + weekday + "，"
                + state + (titles.isEmpty() ? "" : "，" + titles));
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDayClick(day);
            }
        });
    }

    @Override
    public int getItemCount() {
        return days.size();
    }

    private static GradientDrawable todayBackground(Context ctx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ThemeManager.resolveColor(ctx, R.attr.checkin_selected_bg));
        bg.setCornerRadius(ctx.getResources().getDimension(R.dimen.radius_sm));
        return bg;
    }

    private static String monthDay(int dayKey) {
        return (dayKey / 100 % 100) + "月" + (dayKey % 100) + "日";
    }

    private static String weekdayOf(int dayKey) {
        Calendar c = Calendar.getInstance(Locale.ROOT);
        c.clear();
        c.set(dayKey / 10000, dayKey / 100 % 100 - 1, dayKey % 100, 12, 0, 0);
        return WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemCheckinMonthDayBinding b;

        VH(ItemCheckinMonthDayBinding binding) {
            super(binding.getRoot());
            b = binding;
        }
    }
}
