package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.data.entity.Book;

/**
 * 全部作者分布二级页：展示所有作者及对应藏书数量，按数量倒序。
 * 逻辑全部在 {@link DistributionListActivity}，本页只声明「统计作者字段」。
 */
public class AuthorActivity extends DistributionListActivity {

    @Override
    protected String fieldLabel() {
        return "作者";
    }

    @Override
    protected String extractField(Book book) {
        return book.author;
    }
}
