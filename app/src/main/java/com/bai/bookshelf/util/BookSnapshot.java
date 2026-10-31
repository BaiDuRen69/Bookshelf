package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 整行快照（v5.8.16）：批量改动的撤销要把「改动前的整行」写回去，
 * 而批量动作改的是哪几个字段由调用方的 lambda 决定，逐个字段记不现实。
 * Book 全是可序列化字段（基本类型 + String + Long），直接用序列化克隆。
 */
public final class BookSnapshot {

    private BookSnapshot() {
    }

    /** 克隆一行；失败返回 null（调用方据此只提示、不给撤销入口） */
    @androidx.annotation.Nullable
    public static Book copy(@androidx.annotation.Nullable Book book) {
        if (book == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(book);
            }
            ObjectInputStream ois = new ObjectInputStream(
                    new ByteArrayInputStream(bos.toByteArray()));
            Object clone = ois.readObject();
            ois.close();
            return clone instanceof Book ? (Book) clone : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
