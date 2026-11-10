import io

P = 'app/src/main/java/com/bai/bookshelf/ui/stats/StatsFragment.java'
raw = io.open(P, 'rb').read()
lines = raw.split(b'\n')          # lines[i] is 1-based line i+1 (keeps trailing \r)

assert lines[698].strip().endswith(b'private void computeFrom(java.util.List<Book> raw) {'), lines[698]
assert lines[699].strip() == b'BookshelfApp app = BookshelfApp.get();', lines[699]
assert lines[1048].strip() == b'});', lines[1048]
assert lines[1049].strip() == b'}', lines[1049]

body = '''        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            // v5.9.5 卡 2：口径计算整体搬进 StatsComputer（纯 JVM、可读表注入），这里只留线程调度与渲染回调
            StatsComputer.Result r = new StatsComputer(
                    new DbStatsSource(app.db()), nextPickSeed).compute(raw);
            final StatsSnapshot snap = r.snapshot;
            // 种子不变 → 同一批书永远抽到同一本；点「换一本」才换种子
            this.nextPick = r.nextPick;

            app.ui(() -> {
                if (b == null) {
                    return;
                }
                statsComputed = true;
                // 数据变了：四栏都标脏，下次显示时各自重画一次
                java.util.Arrays.fill(tabDirty, true);
                snapshot = snap;
                statsEmpty = snap.total == 0;
                bindReadingGoal(snap.finishedThisYear, snap.avgDays);
                applyStatsTab();
            });
'''.replace('\n', '\r\n')

new = lines[:699] + [body.rstrip('\r\n').encode('utf-8') + b'\r\n'] + lines[1049:]
io.open(P, 'wb').write(b'\n'.join(new))
print('old total lines:', len(lines) - 1, '-> new:', len(new) - 1)
