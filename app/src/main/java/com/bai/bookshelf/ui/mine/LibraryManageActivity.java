package com.bai.bookshelf.ui.mine;

import android.content.Intent;
import android.os.Bundle;

import com.bai.bookshelf.databinding.ActivityLibraryManageBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.location.CategoryActivity;
import com.bai.bookshelf.ui.location.LocationActivity;
import com.bai.bookshelf.ui.location.SourceChannelActivity;
import com.bai.bookshelf.ui.location.TagActivity;

/**
 * 书目管理二级页（v5.7）：把地点/来源/标签/分类/套装分摊从「我的」首屏收进来。
 */
public class LibraryManageActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityLibraryManageBinding b = ActivityLibraryManageBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.rowLocation.setOnClickListener(v ->
                startActivity(new Intent(this, LocationActivity.class)));
        b.rowSource.setOnClickListener(v ->
                startActivity(new Intent(this, SourceChannelActivity.class)));
        b.rowTag.setOnClickListener(v ->
                startActivity(new Intent(this, TagActivity.class)));
        b.rowCategory.setOnClickListener(v ->
                startActivity(new Intent(this, CategoryActivity.class)));
        // v5.8.18：借阅人档案（借过几本 / 有没有逾期 / 手上还剩几本）
        b.rowBorrower.setOnClickListener(v ->
                startActivity(new Intent(this, com.bai.bookshelf.ui.lend.BorrowerActivity.class)));
        b.rowSplit.setOnClickListener(v ->
                startActivity(new Intent(this, SplitActivity.class)));
    }
}
