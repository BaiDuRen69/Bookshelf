package com.bai.bookshelf.ui.edit;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;

import androidx.activity.ComponentActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页的地点与来源下拉（v5.6 从 BookEditActivity 抽出）：
 * 选项加载、同页新增后回选、从管理页返回时保留当前选择，
 * 以及两点各自的脏检查基准。
 */
public class EditPlaceChannels {

    public interface Host {
        Book book();

        boolean editMode();

        /** 地点列表加载完成（逐本信息栏目据此首次构建） */
        void onLocationsReady();
    }

    private final ComponentActivity activity;
    private final ActivityBookEditBinding b;
    private final Host host;

    private List<Location> locations = new ArrayList<>();
    private List<SourceChannel> channels = new ArrayList<>();

    private Long pendingLocationId;   // 同页新增/刷新后要选中的地点
    private String pendingChannelName; // 同页新增/刷新后要选中的渠道

    private Long initialLocationId;   // 脏检查基准（首次加载后记录）
    private String initialChannel;
    private boolean baselineLocSet = false;
    private boolean baselineChSet = false;

    private boolean needRefresh = false; // 从管理页返回后要重载两个下拉

    public EditPlaceChannels(ComponentActivity activity, ActivityBookEditBinding b, Host host) {
        this.activity = activity;
        this.b = b;
        this.host = host;
    }

    public List<Location> locations() {
        return locations;
    }

    public List<SourceChannel> channels() {
        return channels;
    }

    public Long initialLocationId() {
        return initialLocationId;
    }

    public String initialChannel() {
        return initialChannel;
    }

    /** 当前地点下拉的选中 id（未选/未设置为 null） */
    public Long currentLocationId() {
        int pos = b.spLocation.getSelectedItemPosition();
        return (pos > 0 && pos <= locations.size()) ? locations.get(pos - 1).id : null;
    }

    /** 当前来源二级下拉的选中名称（未选为 ""） */
    public String currentChannelName() {
        int pos = b.spSourceChannel.getSelectedItemPosition();
        return (pos > 0 && pos <= channels.size()) ? channels.get(pos - 1).name : "";
    }

    /** 同页新增地点后自动选中它 */
    public void addAndSelectLocation(long id) {
        pendingLocationId = id;
        loadLocations();
    }

    /** 同页新增渠道后自动选中它 */
    public void addAndSelectChannel(String name, int ownType) {
        pendingChannelName = name;
        loadChannels(ownType);
    }

    /** 跳转管理页前标记：返回后重载并保留当前选择 */
    public void markNeedRefresh() {
        needRefresh = true;
    }

    /** onResume 时调用：从管理页返回后重载两个下拉 */
    public void refreshIfNeeded() {
        if (!needRefresh) {
            return;
        }
        needRefresh = false;
        int locPos = b.spLocation.getSelectedItemPosition();
        if (locPos > 0 && locPos <= locations.size()) {
            pendingLocationId = locations.get(locPos - 1).id;
        }
        int chPos = b.spSourceChannel.getSelectedItemPosition();
        if (chPos > 0 && chPos <= channels.size()) {
            pendingChannelName = channels.get(chPos - 1).name;
        }
        loadLocations();
        loadChannels(b.spOwn.getSelectedItemPosition());
    }

    /** 地点下拉：第一项「未设置」；新增模式下自动选中默认地点 */
    public void loadLocations() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Location> locs = app.db().locationDao().getAll();
            app.ui(() -> {
                locations = locs;
                List<String> names = new ArrayList<>();
                names.add("（未设置）");
                for (Location loc : locs) {
                    names.add(loc.name + (loc.isDefault ? "（默认）" : ""));
                }
                b.spLocation.setAdapter(new ArrayAdapter<>(activity,
                        android.R.layout.simple_spinner_dropdown_item, names));
                Book book = host.book();
                if (pendingLocationId != null) {
                    // 同页新增后自动选中新地点
                    for (int i = 0; i < locs.size(); i++) {
                        if (locs.get(i).id == pendingLocationId) {
                            b.spLocation.setSelection(i + 1);
                            break;
                        }
                    }
                    pendingLocationId = null;
                } else if (book.locationId != null) {
                    // 编辑模式：回显已选地点
                    for (int i = 0; i < locs.size(); i++) {
                        if (locs.get(i).id == book.locationId) {
                            b.spLocation.setSelection(i + 1);
                            break;
                        }
                    }
                } else if (!host.editMode()) {
                    // 新增模式：优先选「上次使用的地点」，没有则选默认地点
                    SharedPreferences sp = activity.getSharedPreferences(
                            "ui_prefs", Context.MODE_PRIVATE);
                    long lastId = sp.getLong("last_location_id", -1);
                    int sel = -1;
                    if (lastId > 0) {
                        for (int i = 0; i < locs.size(); i++) {
                            if (locs.get(i).id == lastId) {
                                sel = i + 1;
                                break;
                            }
                        }
                    }
                    if (sel < 0) {
                        for (int i = 0; i < locs.size(); i++) {
                            if (locs.get(i).isDefault) {
                                sel = i + 1;
                                break;
                            }
                        }
                    }
                    if (sel > 0) {
                        b.spLocation.setSelection(sel);
                    }
                }
                // 记录脏检查基准（仅首次加载）
                if (!baselineLocSet) {
                    baselineLocSet = true;
                    initialLocationId = currentLocationId();
                }
                host.onLocationsReady();
            });
        });
    }

    /** 来源二级选项下拉：按大类过滤，第一项「（无）」，值为名称字符串 */
    public void loadChannels(int ownType) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<SourceChannel> list = app.db().sourceChannelDao().getByType(ownType);
            app.ui(() -> {
                channels = list;
                List<String> names = new ArrayList<>();
                names.add("（无）");
                for (SourceChannel ch : list) {
                    names.add(ch.name);
                }
                b.spSourceChannel.setAdapter(new ArrayAdapter<>(activity,
                        android.R.layout.simple_spinner_dropdown_item, names));
                Book book = host.book();
                // 同页新增后自动选中
                if (pendingChannelName != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).name.equals(pendingChannelName)) {
                            b.spSourceChannel.setSelection(i + 1);
                            break;
                        }
                    }
                    pendingChannelName = null;
                } else if (!book.sourceChannel.isEmpty()) {
                    // 回显已有选项（切了大类后可能匹配不上，则回到「无」）
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).name.equals(book.sourceChannel)) {
                            b.spSourceChannel.setSelection(i + 1);
                            break;
                        }
                    }
                }
                // 记录脏检查基准（仅首次加载；切换大类不重置——那本身就是改动）
                if (!baselineChSet) {
                    baselineChSet = true;
                    initialChannel = currentChannelName();
                }
            });
        });
    }
}
