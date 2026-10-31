package com.bai.bookshelf.data;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.bai.bookshelf.data.dao.BookCopyDao;
import com.bai.bookshelf.data.dao.BookDao;
import com.bai.bookshelf.data.dao.BorrowRecordDao;
import com.bai.bookshelf.data.dao.BorrowerDao;
import com.bai.bookshelf.data.dao.CategoryDao;
import com.bai.bookshelf.data.dao.CountryDao;
import com.bai.bookshelf.data.dao.ExcerptDao;
import com.bai.bookshelf.data.dao.LocationDao;
import com.bai.bookshelf.data.dao.NoteImageDao;
import com.bai.bookshelf.data.dao.PublisherDao;
import com.bai.bookshelf.data.dao.SourceChannelDao;
import com.bai.bookshelf.data.dao.TagDao;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Borrower;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Country;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.Publisher;
import com.bai.bookshelf.data.entity.WishBook;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.data.entity.Tag;

/**
 * Room 数据库入口。
 * v2：books 增加 印次/国家/豆瓣评分/字数/分类/标签/来源渠道 字段，
 *     locations 增加默认标记，新增 source_channels 表。
 * v3：修复 v2 迁移的列可空性问题（Room 对 Java String 字段期望可空 TEXT，
 *     v2 误写成 NOT NULL 导致迁移校验失败、打开即闪退），重建两张表修正。
 * v4：source_channels 增加 ownType 列，二级来源按大类分组（自购/借阅/赠书各自独立列表）。
 * v5：新增 tags 标签库表（微信式多标签）。
 * v6：books 增加 quantity（拥有数量，默认 1），新增 categories 分类库表。
 * v7：borrow_records 增加 note（借出备注），books 增加 locationDetail（各本存放位置），
 *     新增 borrowers 常用借阅人表。
 * v8：新增 book_copies 逐本副本信息表（同款多本时，第 2 本起的逐本状态/地点/来源/价格）。
 * v9：book_copies 加 note（逐本备注），新增 countries 国家/地区库表。
 * v10：borrow_records 加 copyIndex（借出挂到具体某一本），新增 note_images 备注图片表。
 * v11：note_images 加 ownerKey 列，支持书级/逐本/借出记录三种备注图片；存量数据回填为 book:{bookId}。
 * v12：新增出版社库表 publishers。
 * v13：categories 加 parentId 列实现二级分类（null=一级）；默认分类树由
 *      CategorySeeder.seedIfMissing 在每次启动时幂等补种（兼容旧备份恢复）。
 * v14：borrowers 加 phone（借阅人联系方式）。
 * v15：books 加阅读记录三列（开始/读完/进度）。
 * v16：新增 wish_books 想读清单表。
 * v17：books 加 review（个人短评），新增 excerpts 摘抄表。
 * v18：books 加系列三列（系列名/卷号/阅读顺序），支撑系列与套装分组。
 * v19：wish_books 加 sort，支撑想读清单手动拖动排序。
 * v20：books 加借阅时间段与归还日期（来源=借阅时使用）。
 * v21：books 加 readingRecords（多次阅读记录 JSON）与弃读状态。
 */
@Database(entities = {Book.class, Location.class, BorrowRecord.class, SourceChannel.class, Tag.class,
        Category.class, Borrower.class, BookCopy.class, Country.class, NoteImage.class, Publisher.class, WishBook.class,
        Excerpt.class},
        version = AppDatabase.VERSION, exportSchema = true) // v4.0：导出 schema JSON 供迁移比对与测试
public abstract class AppDatabase extends RoomDatabase {

    /** Room schema 版本（v5.7：备份 meta 与升级前副本都要读到它，故提为常量） */
    public static final int VERSION = 24;

    /** 数据库文件名（建库与升级前副本共用） */
    public static final String DB_NAME = "bookshelf.db";

    public abstract BookDao bookDao();

    public abstract LocationDao locationDao();

    public abstract BorrowRecordDao borrowRecordDao();

    public abstract com.bai.bookshelf.data.dao.WishBookDao wishBookDao();

    public abstract ExcerptDao excerptDao();

    public abstract SourceChannelDao sourceChannelDao();

    public abstract TagDao tagDao();

    public abstract CategoryDao categoryDao();

    public abstract BorrowerDao borrowerDao();

    public abstract BookCopyDao bookCopyDao();

    public abstract CountryDao countryDao();

    public abstract NoteImageDao noteImageDao();

    public abstract PublisherDao publisherDao();

    /** v1 → v2 迁移：纯加列/加表。注意 String 字段在 Room 中是可空列，只能写 TEXT DEFAULT '' */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // books 新增 7 个可空文本列
            database.execSQL("ALTER TABLE books ADD COLUMN printing TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN country TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN doubanRating TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN wordCount TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN category TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN tags TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN sourceChannel TEXT DEFAULT ''");
            // locations 新增默认标记（boolean 是基本类型，NOT NULL 正确）
            database.execSQL("ALTER TABLE locations ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0");
            // 来源渠道表（name 可空，与 Room 期望一致）
            database.execSQL("CREATE TABLE IF NOT EXISTS `source_channels` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`sort` INTEGER NOT NULL)");
        }
    };

    /**
     * v2 → v3 修复迁移：按 Room 期望的 schema 重建 books 和 source_channels。
     * 同时服务于两类设备：
     * - 装了出错 v1.1 的设备（列是 NOT NULL，打开即闪退）：重建后恢复正常，数据保留；
     * - 从 v1.0 升级的设备（刚跑完修正后的 1→2）：重建是无副作用的同构操作。
     * SQLite 不支持修改列约束，只能「建新表 → 拷数据 → 删旧表 → 改名」。
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // ---- 重建 books ----
            database.execSQL("DROP TABLE IF EXISTS `books_new`");
            database.execSQL("CREATE TABLE `books_new` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`isbn` TEXT, `title` TEXT, `subtitle` TEXT, `author` TEXT, `translator` TEXT, "
                    + "`publisher` TEXT, `publishDate` TEXT, "
                    + "`pages` INTEGER NOT NULL, `listPrice` REAL NOT NULL, "
                    + "`coverUrl` TEXT, `summary` TEXT, "
                    + "`printing` TEXT DEFAULT '', `country` TEXT DEFAULT '', "
                    + "`doubanRating` TEXT DEFAULT '', `wordCount` TEXT DEFAULT '', "
                    + "`category` TEXT DEFAULT '', `tags` TEXT DEFAULT '', "
                    + "`locationId` INTEGER, "
                    + "`stockStatus` INTEGER NOT NULL, `readStatus` INTEGER NOT NULL, `ownType` INTEGER NOT NULL, "
                    + "`sourceChannel` TEXT DEFAULT '', "
                    + "`purchaseDate` TEXT, `purchasePrice` REAL NOT NULL, "
                    + "`rating` INTEGER NOT NULL, `note` TEXT, "
                    + "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `dirty` INTEGER NOT NULL)");
            // 显式列名拷贝，避免依赖列顺序
            database.execSQL("INSERT INTO `books_new` ("
                    + "`id`, `isbn`, `title`, `subtitle`, `author`, `translator`, `publisher`, `publishDate`, "
                    + "`pages`, `listPrice`, `coverUrl`, `summary`, "
                    + "`printing`, `country`, `doubanRating`, `wordCount`, `category`, `tags`, "
                    + "`locationId`, `stockStatus`, `readStatus`, `ownType`, `sourceChannel`, "
                    + "`purchaseDate`, `purchasePrice`, `rating`, `note`, `createdAt`, `updatedAt`, `dirty`) "
                    + "SELECT "
                    + "`id`, `isbn`, `title`, `subtitle`, `author`, `translator`, `publisher`, `publishDate`, "
                    + "`pages`, `listPrice`, `coverUrl`, `summary`, "
                    + "`printing`, `country`, `doubanRating`, `wordCount`, `category`, `tags`, "
                    + "`locationId`, `stockStatus`, `readStatus`, `ownType`, `sourceChannel`, "
                    + "`purchaseDate`, `purchasePrice`, `rating`, `note`, `createdAt`, `updatedAt`, `dirty` "
                    + "FROM `books`");
            database.execSQL("DROP TABLE `books`");
            database.execSQL("ALTER TABLE `books_new` RENAME TO `books`");

            // ---- 重建 source_channels（name 改可空） ----
            database.execSQL("DROP TABLE IF EXISTS `source_channels_new`");
            database.execSQL("CREATE TABLE `source_channels_new` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`sort` INTEGER NOT NULL)");
            database.execSQL("INSERT INTO `source_channels_new` (`id`, `name`, `sort`) "
                    + "SELECT `id`, `name`, `sort` FROM `source_channels`");
            database.execSQL("DROP TABLE `source_channels`");
            database.execSQL("ALTER TABLE `source_channels_new` RENAME TO `source_channels`");
        }
    };

    /** v3 → v4：source_channels 加 ownType 列；存量数据都是购买渠道，默认归自购(0) 正合适 */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE source_channels ADD COLUMN ownType INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v4 → v5：新增标签库表 */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `tags` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`sort` INTEGER NOT NULL)");
        }
    };

    /** v5 → v6：books 加拥有数量列（基本类型 int，NOT NULL 正确）；新增分类库表 */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1");
            database.execSQL("CREATE TABLE IF NOT EXISTS `categories` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`sort` INTEGER NOT NULL)");
        }
    };

    /** v6 → v7：借出备注 / 各本存放位置 两个可空文本列 + 常用借阅人表 */
    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE borrow_records ADD COLUMN note TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN locationDetail TEXT DEFAULT ''");
            database.execSQL("CREATE TABLE IF NOT EXISTS `borrowers` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`sort` INTEGER NOT NULL)");
        }
    };

    /** v7 → v8：新增逐本副本信息表（String 列一律可空 TEXT，与 Room 期望一致） */
    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `book_copies` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`bookId` INTEGER NOT NULL, "
                    + "`copyIndex` INTEGER NOT NULL, "
                    + "`stockStatus` INTEGER NOT NULL, "
                    + "`locationId` INTEGER, "
                    + "`ownType` INTEGER NOT NULL, "
                    + "`sourceChannel` TEXT DEFAULT '', "
                    + "`purchaseDate` TEXT DEFAULT '', "
                    + "`purchasePrice` REAL NOT NULL)");
        }
    };

    /** v8 → v9：副本备注列 + 国家/地区库表（String 列一律可空 TEXT） */
    public static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE book_copies ADD COLUMN note TEXT DEFAULT ''");
            database.execSQL("CREATE TABLE IF NOT EXISTS `countries` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`sort` INTEGER NOT NULL)");
        }
    };

    /**
     * v9 → v10：借出记录加 copyIndex（借出挂到具体某一本，1 = 第 1 本/主记录，
     * 存量记录默认 1 与旧行为一致）；新增 note_images 备注图片表。
     */
    public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE borrow_records ADD COLUMN copyIndex INTEGER NOT NULL DEFAULT 1");
            database.execSQL("CREATE TABLE IF NOT EXISTS `note_images` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`bookId` INTEGER NOT NULL, "
                    + "`path` TEXT DEFAULT '', "
                    + "`sort` INTEGER NOT NULL)");
        }
    };

    /**
     * v10 → v11：note_images 加 ownerKey 列，实现一套表支持三种备注图片。
     * 存量图片的 ownerKey 回填为 "book:{bookId}"，与新版书级备注查询一致。
     */
    public static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE note_images ADD COLUMN ownerKey TEXT DEFAULT ''");
            database.execSQL("UPDATE note_images SET ownerKey = 'book:' || bookId WHERE ownerKey = ''");
        }
    };

    /**
     * v11 → v12：新增出版社库表 publishers，支持编辑页快速选择。
     */
    public static final Migration MIGRATION_11_12 = new Migration(11, 12) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `publishers` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`sort` INTEGER NOT NULL)");
        }
    };

    /**
     * v12 → v13：categories 加 parentId 列（可空 INTEGER，null = 一级分类）。
     * 默认分类树不在此播种——Migration 里不便复用 DAO，统一由
     * CategorySeeder.seedIfMissing 在应用启动（ensureDefaultData）与备份恢复后幂等补种。
     */
    public static final Migration MIGRATION_12_13 = new Migration(12, 13) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE categories ADD COLUMN parentId INTEGER");
        }
    };

    /** v4.48：想读清单 */
    /** v16 → v17：个人短评列 + 摘抄表（字符串列可空 TEXT，与 Room 期望一致） */
    public static final Migration MIGRATION_16_17 = new Migration(16, 17) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN review TEXT DEFAULT ''");
            database.execSQL("CREATE TABLE IF NOT EXISTS `excerpts` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`bookId` INTEGER NOT NULL, "
                    + "`page` INTEGER NOT NULL DEFAULT 0, "
                    + "`content` TEXT DEFAULT '', "
                    + "`note` TEXT DEFAULT '', "
                    + "`tags` TEXT DEFAULT '', "
                    + "`imagePath` TEXT DEFAULT '', "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL)");
        }
    };

    public static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `wish_books` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "`title` TEXT, `author` TEXT, `isbn` TEXT,"
                    + "`priority` INTEGER NOT NULL, `expectedPrice` REAL NOT NULL,"
                    + "`note` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)");
        }
    };

    /** v4.44：阅读记录（开始/读完日期 + 进度） */
    public static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN readingStartDate TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN readingFinishDate TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN readingProgress INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v4.53：系列 / 套装分组（系列名 + 卷号 + 阅读顺序） */
    public static final Migration MIGRATION_17_18 = new Migration(17, 18) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN seriesName TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN seriesVolume TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN seriesOrder INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v5.7：多次阅读记录 */
    public static final Migration MIGRATION_20_21 = new Migration(20, 21) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN readingRecords TEXT DEFAULT ''");
        }
    };

    /** v5.7：阅读记录平均评分（0.5 步进；老书的 rating 先回填，编辑保存后再按记录重算） */
    public static final Migration MIGRATION_21_22 = new Migration(21, 22) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN ratingAvg REAL NOT NULL DEFAULT 0");
            database.execSQL("UPDATE books SET ratingAvg = rating WHERE rating > 0");
        }
    };

    /**
     * v5.8.20：给热查列补索引（此前全项目 0 个 @Index，1000 本时详情页要全表扫）。
     * 索引名必须与 Room 按 @Index 生成的名字一致（index_表_列[_列]），否则升级后
     * 校验 schema 会报「Missing index」。
     */
    public static final Migration MIGRATION_23_24 = new Migration(23, 24) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS index_books_isbn ON books(isbn)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_book_copies_bookId_copyIndex "
                    + "ON book_copies(bookId, copyIndex)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_excerpts_bookId ON excerpts(bookId)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_borrow_records_bookId_status "
                    + "ON borrow_records(bookId, status)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_borrow_records_status_dueDate "
                    + "ON borrow_records(status, dueDate)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_note_images_ownerKey "
                    + "ON note_images(ownerKey)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_note_images_bookId "
                    + "ON note_images(bookId)");
        }
    };

    /** v5.7：多次借书记录；已归还的借阅书统一落成「电子书签」藏书状态 */
    public static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN borrowRecords TEXT DEFAULT ''");
            database.execSQL("UPDATE books SET stockStatus = 3 "
                    + "WHERE ownType = 1 AND borrowReturnDate IS NOT NULL AND borrowReturnDate != '' "
                    + "AND stockStatus NOT IN (1, 2)");
        }
    };

    /** v5.7：来源为借阅的书记录借阅时间段与归还日期 */
    public static final Migration MIGRATION_19_20 = new Migration(19, 20) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE books ADD COLUMN borrowStartDate TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN borrowDueDate TEXT DEFAULT ''");
            database.execSQL("ALTER TABLE books ADD COLUMN borrowReturnDate TEXT DEFAULT ''");
        }
    };

    /** v5.7：想读清单手动排序值 */
    public static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE wish_books ADD COLUMN sort INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v4.42：借阅人补联系方式（催还文案要用） */
    public static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE borrowers ADD COLUMN phone TEXT DEFAULT ''");
        }
    };

    /** 全部迁移（建库与迁移冒烟测试共用一份清单，避免两处各写一遍漏掉一条） */
    public static Migration[] migrations() {
        return new Migration[]{
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24};
    }

    /** 某一个旧版本对应的迁移；没有则 null（供测试逐级串起来） */
    public static Migration migration(int fromVersion) {
        for (Migration m : migrations()) {
            if (m.startVersion == fromVersion) {
                return m;
            }
        }
        return null;
    }
}
