package dev.mr.localledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Local structured ledger only. No notification or screen body is stored. */
public final class LedgerDb extends SQLiteOpenHelper {
    public static final String CONFIRMED = "confirmed";
    public static final String PENDING = "pending";
    private static final String DELETED = "deleted";
    private static final int AUTO_THRESHOLD = 85;
    private static final long DUPLICATE_WINDOW_MS = 120_000L;
    private static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000;
    private long lastMaintenanceAt;

    public LedgerDb(Context context) {
        super(context.getApplicationContext(), "local-ledger.db", null, 2);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // Best-effort removal from SQLite free pages; this cannot erase external device backups.
        if (!db.isReadOnly()) {
            try (Cursor c = db.rawQuery("PRAGMA secure_delete=ON", null)) { c.moveToFirst(); }
        }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, amount_fen INTEGER NOT NULL,"
                + "direction TEXT NOT NULL,merchant TEXT NOT NULL,category TEXT NOT NULL,status TEXT NOT NULL,"
                + "confidence INTEGER NOT NULL,reason TEXT NOT NULL,source_package TEXT NOT NULL,"
                + "source_label TEXT NOT NULL,source_kind TEXT NOT NULL,transaction_id TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,note TEXT NOT NULL,"
                + "needs_time_confirmation INTEGER NOT NULL DEFAULT 0,captured_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX entries_time ON entries(created_at DESC)");
        db.execSQL("CREATE INDEX entries_transaction ON entries(source_package,transaction_id)");
        db.execSQL("CREATE INDEX entries_amount ON entries(amount_fen,direction,created_at)");
        db.execSQL("CREATE TABLE captures (event_hash TEXT PRIMARY KEY,entry_id INTEGER NOT NULL,observed_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX captures_observed_time ON captures(observed_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN needs_time_confirmation INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE entries ADD COLUMN captured_at INTEGER NOT NULL DEFAULT 0");
            // The old version did not persist capture time, so zero honestly means unknown.
            // Version 1 treated the moment a history page was read as its transaction date.
            // Preserve every old entry, but require that date to be explicitly corrected/confirmed.
            db.execSQL("UPDATE entries SET needs_time_confirmation=1 WHERE source_kind='accessibility' AND status<>'deleted'");
            db.execSQL("CREATE INDEX IF NOT EXISTS captures_observed_time ON captures(observed_at)");
            db.execSQL("DROP INDEX IF EXISTS entries_transaction");
            db.execSQL("CREATE INDEX entries_transaction ON entries(source_package,transaction_id)");
            try (Cursor c = db.rawQuery("SELECT id,transaction_id,updated_at FROM entries WHERE status='deleted'", null)) {
                while (c.moveToNext()) {
                    db.update("entries", deletedValues(c.getString(1), c.getLong(2)), "id=?",
                            new String[]{Long.toString(c.getLong(0))});
                }
            }
        }
        if (newVersion > 2) throw new IllegalStateException("账本数据库需要专用增量迁移，禁止覆盖个人账本");
    }

    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (db.isReadOnly()) return;
        db.beginTransaction();
        try {
            maintain(db, System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public static final class Entry {
        public long id;
        public long amountFen;
        public String direction;
        public String merchant;
        public String category;
        public String status;
        public int confidence;
        public String reason;
        public String sourcePackage;
        public String sourceLabel;
        public String sourceKind;
        public String transactionId;
        public long createdAt;
        public long updatedAt;
        public String note;
        public boolean needsTimeConfirmation;
        public long capturedAt;
    }

    public static final class Totals {
        public long expenseFen;
        public long incomeFen;
        public long refundFen;
        public long transferFen;
        public int pendingCount;
    }

    /** Confirmed amounts for one category in the requested month; refunds stay separate. */
    public static final class CategoryTotal {
        public String name;
        public long expenseFen;
        public long incomeFen;
        public long refundFen;
        public long transferFen;
        public int entryCount;
    }

    /** One day of a requested month, including zero-amount days for a complete calendar. */
    public static final class DayTotal {
        public int day;
        public long expenseFen;
        public long incomeFen;
        public long refundFen;
        public long transferFen;
        public int entryCount;
    }

    public synchronized String ingest(PaymentEvent event) {
        RecognitionEngine.Draft draft = RecognitionEngine.parse(event);
        if (draft == null) return "未发现可记录的交易结果";
        // Empty keys must never collapse every future notification from a package into one.
        boolean stableKey = !event.eventKey.isEmpty();
        String hash = stableKey ? sha256(event.sourcePackage + "\n" + event.sourceKind + "\n" + event.eventKey) : "";
        long transactionAt = draft.transactionAt > 0 ? draft.transactionAt : event.observedAt;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            maintain(db, System.currentTimeMillis());
            boolean eventConflict = false;
            if (stableKey) {
                try (Cursor c = db.rawQuery("SELECT e.id,e.amount_fen,e.direction,e.status FROM captures c LEFT JOIN entries e ON e.id=c.entry_id WHERE c.event_hash=?",
                        new String[]{hash})) {
                    if (c.moveToFirst()) {
                        if (c.isNull(0) || DELETED.equals(c.getString(3))
                                || (c.getLong(1) == draft.amountFen && draft.direction.equals(c.getString(2)))) {
                            boolean supplemented = !c.isNull(0) && supplementPendingMerchant(db, c.getLong(0), draft);
                            db.setTransactionSuccessful();
                            return supplemented ? "已补充待确认记录的商户" : "相同采集事件已处理";
                        }
                        // A reused event ID may carry corrected data: preserve a reviewable conflict.
                        eventConflict = true;
                    }
                }
                if (eventConflict) {
                    hash = sha256(hash + "\n" + draft.direction + "\n" + draft.amountFen);
                    try (Cursor c = db.rawQuery("SELECT entry_id FROM captures WHERE event_hash=?", new String[]{hash})) {
                        if (c.moveToFirst()) {
                            boolean supplemented = supplementPendingMerchant(db, c.getLong(0), draft);
                            db.setTransactionSuccessful();
                            return supplemented ? "已补充待确认记录的商户" : "同一采集冲突已保留待确认";
                        }
                    }
                }
            }
            TransactionMatch existing = existingTransaction(db, event, draft);
            if (existing.sameId >= 0) {
                boolean supplemented = supplementPendingMerchant(db, existing.sameId, draft);
                if (stableKey && !exists(db, "SELECT 1 FROM captures WHERE event_hash=?", new String[]{hash})) {
                    remember(db, hash, existing.sameId);
                }
                db.setTransactionSuccessful();
                return supplemented ? "已补充待确认记录的商户" : "同一交易号与金额已记录或已删除";
            }
            boolean possibleDuplicate = exists(db,
                    "SELECT 1 FROM entries WHERE status<>? AND amount_fen=? AND direction=? AND created_at BETWEEN ? AND ? LIMIT 1",
                    new String[]{DELETED, Long.toString(draft.amountFen), draft.direction,
                            Long.toString(transactionAt - DUPLICATE_WINDOW_MS),
                            Long.toString(transactionAt + DUPLICATE_WINDOW_MS)});
            boolean pending = draft.confidence < AUTO_THRESHOLD || possibleDuplicate || !stableKey
                    || eventConflict || existing.conflict || draft.needsTimeConfirmation;
            String reason = draft.reason;
            if (possibleDuplicate) reason += "；短时间存在同额记录，疑似重复，请核对（未自动合并）";
            if (!stableKey) reason += "；来源未提供稳定事件标识，请核对";
            if (eventConflict) reason += "；同一采集标识的金额或类型发生变化，已保留冲突候选";
            if (existing.conflict) reason += "；相同交易号存在不同金额或类型，请核对，未覆盖旧账";
            ContentValues values = values(draft.amountFen, draft.direction, draft.merchant, draft.category,
                    pending ? PENDING : CONFIRMED, draft.confidence, reason, event.sourcePackage,
                    event.sourceLabel, event.sourceKind, draft.transactionId, transactionAt, "");
            values.put("needs_time_confirmation", draft.needsTimeConfirmation ? 1 : 0);
            values.put("captured_at", System.currentTimeMillis());
            long id = db.insertOrThrow("entries", null, values);
            if (stableKey) remember(db, hash, id);
            db.setTransactionSuccessful();
            return pending ? "新增一笔待确认记录" : "已自动记账";
        } finally {
            db.endTransaction();
        }
    }

    /** Only enrich an exact duplicate whose untouched pending entry still lacks a merchant. */
    private static boolean supplementPendingMerchant(SQLiteDatabase db, long id, RecognitionEngine.Draft draft) {
        String merchant = clean(draft.merchant, 120);
        if (merchant.isEmpty() || "待补充商户".equals(merchant)) return false;
        String selection = "id=? AND status=? AND merchant=? AND amount_fen=? AND direction=?";
        String[] args = {Long.toString(id), PENDING, "待补充商户", Long.toString(draft.amountFen), draft.direction};
        ContentValues values = new ContentValues();
        try (Cursor c = db.query("entries", new String[]{"category"}, selection, args, null, null, null, "1")) {
            if (!c.moveToFirst()) return false;
            values.put("merchant", merchant);
            String category = clean(draft.category, 60);
            if ("其他".equals(c.getString(0)) && !category.isEmpty()) values.put("category", category);
        }
        // Preserve transaction/capture dates, amount, status, confidence, source, reason and user notes.
        return db.update("entries", values, selection, args) == 1;
    }

    private static final class TransactionMatch {
        long sameId = -1;
        boolean conflict;
    }

    private static TransactionMatch existingTransaction(SQLiteDatabase db, PaymentEvent event, RecognitionEngine.Draft draft) {
        TransactionMatch result = new TransactionMatch();
        if (draft.transactionId.isEmpty()) return result;
        try (Cursor c = db.rawQuery("SELECT id,amount_fen,direction,status FROM entries WHERE source_package=? AND transaction_id IN (?,?) ORDER BY id DESC",
                new String[]{event.sourcePackage, draft.transactionId, "sha256:" + sha256(draft.transactionId)})) {
            while (c.moveToNext()) {
                if (DELETED.equals(c.getString(3))
                        || (c.getLong(1) == draft.amountFen && draft.direction.equals(c.getString(2)))) {
                    result.sameId = c.getLong(0);
                    return result;
                }
                result.conflict = true;
            }
        }
        return result;
    }

    private static void remember(SQLiteDatabase db, String hash, long id) {
        ContentValues values = new ContentValues();
        values.put("event_hash", hash);
        values.put("entry_id", id);
        values.put("observed_at", System.currentTimeMillis());
        db.insertOrThrow("captures", null, values);
        trimCaptures(db);
    }

    private static void trimCaptures(SQLiteDatabase db) {
        db.execSQL("DELETE FROM captures WHERE event_hash IN (SELECT event_hash FROM captures ORDER BY observed_at DESC,rowid DESC LIMIT -1 OFFSET 20000)");
    }

    private static void trimDeleted(SQLiteDatabase db) {
        db.execSQL("DELETE FROM entries WHERE id IN (SELECT id FROM entries WHERE status='deleted' ORDER BY updated_at DESC,id DESC LIMIT -1 OFFSET 5000)");
    }

    private void maintain(SQLiteDatabase db, long now) {
        if (lastMaintenanceAt > 0 && now >= lastMaintenanceAt && now - lastMaintenanceAt < 60_000L) return;
        long cutoff = now - RETENTION_MS;
        db.delete("captures", "observed_at<?", new String[]{Long.toString(cutoff)});
        trimCaptures(db);
        db.delete("entries", "status=? AND updated_at<?", new String[]{DELETED, Long.toString(cutoff)});
        trimDeleted(db);
        lastMaintenanceAt = now;
    }

    private static boolean exists(SQLiteDatabase db, String query, String[] args) {
        try (Cursor c = db.rawQuery(query, args)) { return c.moveToFirst(); }
    }

    /** Most recent 500 visible entries, ordered by capture time for review; CSV export is not capped. */
    public synchronized List<Entry> list(boolean pendingOnly) {
        String selection = pendingOnly ? "status<>? AND (status=? OR needs_time_confirmation=1)" : "status<>?";
        String[] args = pendingOnly ? new String[]{DELETED, PENDING} : new String[]{DELETED};
        String order = pendingOnly ? "captured_at DESC,id DESC" : "created_at DESC,id DESC";
        List<Entry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", null, selection, args,
                null, null, order, "500")) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    /** All visible entries with a reliable date in this month; no 500-entry truncation. */
    public synchronized List<Entry> listMonth(int year, int month) {
        DateRange range = monthRange(year, month);
        return listRange(range);
    }

    /** All visible entries on the selected local day; pending items retain their status. */
    public synchronized List<Entry> listDay(int year, int month, int day) {
        LocalDate date = checkedDate(year, month, day);
        return listRange(new DateRange(date, date.plusDays(1)));
    }

    private List<Entry> listRange(DateRange range) {
        List<Entry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", null,
                "status<>? AND needs_time_confirmation=0 AND created_at>=? AND created_at<?",
                new String[]{DELETED, Long.toString(range.from), Long.toString(range.until)},
                null, null, "created_at DESC,id DESC")) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    /**
     * Literal substring search over structured ledger fields, not captured notification bodies.
     * Searches merchant/category/note/source/transaction ID, plus an exact CNY amount if numeric.
     * At most the latest 500 matching visible entries are returned, including pending entries.
     */
    public synchronized List<Entry> search(String query) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) return list(false);
        if (text.length() > 200) throw new IllegalArgumentException("搜索词最多200个字符");
        String pattern = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String selection = "status<>? AND (merchant LIKE ? ESCAPE '!' OR category LIKE ? ESCAPE '!'"
                + " OR note LIKE ? ESCAPE '!' OR source_label LIKE ? ESCAPE '!' OR transaction_id LIKE ? ESCAPE '!'";
        List<String> args = new ArrayList<>();
        args.add(DELETED);
        for (int i = 0; i < 5; i++) args.add(pattern);
        if (text.matches("[0-9]{1,9}(?:\\.[0-9]{1,2})?")) {
            try {
                long amountFen = new BigDecimal(text).movePointRight(2).longValueExact();
                if (amountFen > 0 && amountFen <= 999_999_999_99L) {
                    selection += " OR amount_fen=?";
                    args.add(Long.toString(amountFen));
                }
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Non-exact amounts still work as ordinary literal text searches.
            }
        }
        selection += ")";
        List<Entry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", null, selection, args.toArray(new String[0]),
                null, null, "created_at DESC,id DESC", "500")) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    public synchronized void confirm(long id) {
        if (exists(getReadableDatabase(), "SELECT 1 FROM entries WHERE id=? AND status<>? AND needs_time_confirmation=1",
                new String[]{Long.toString(id), DELETED})) {
            throw new IllegalStateException("交易日期不明确，请编辑并确认日期后再入账");
        }
        ContentValues values = new ContentValues();
        values.put("status", CONFIRMED);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("entries", values, "id=? AND status=?", new String[]{Long.toString(id), PENDING});
    }

    /** Tombstones retain deduplication identities so a replay cannot restore a deleted entry. */
    public synchronized void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String transactionId = "";
            try (Cursor c = db.rawQuery("SELECT transaction_id FROM entries WHERE id=?", new String[]{Long.toString(id)})) {
                if (c.moveToFirst()) transactionId = c.getString(0);
            }
            db.update("entries", deletedValues(transactionId, System.currentTimeMillis()), "id=?", new String[]{Long.toString(id)});
            maintain(db, System.currentTimeMillis());
            trimDeleted(db);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static ContentValues deletedValues(String transactionId, long deletedAt) {
        ContentValues values = new ContentValues();
        values.put("status", DELETED);
        values.put("amount_fen", 0);
        values.put("direction", "");
        values.put("merchant", "");
        values.put("category", "");
        values.put("note", "");
        values.put("reason", "");
        values.put("source_label", "");
        values.put("source_kind", "");
        values.put("confidence", 0);
        values.put("created_at", 0);
        values.put("captured_at", 0);
        values.put("needs_time_confirmation", 0);
        values.put("transaction_id", transactionId.isEmpty() || transactionId.startsWith("sha256:")
                ? transactionId : "sha256:" + sha256(transactionId));
        values.put("updated_at", deletedAt);
        return values;
    }

    public synchronized long insertManual(long amountFen, String direction, String merchant,
                                           String category, long timestamp, String note) {
        validate(amountFen, direction);
        long createdAt = timestamp > 0 ? timestamp : System.currentTimeMillis();
        validateTimestamp(createdAt);
        String safeMerchant = clean(merchant, 120);
        if (safeMerchant.isEmpty()) safeMerchant = "手动记录";
        String safeCategory = clean(category, 60);
        if (safeCategory.isEmpty()) safeCategory = RecognitionEngine.category(safeMerchant, direction);
        ContentValues values = values(amountFen, direction, safeMerchant, safeCategory, CONFIRMED,
                100, "手动录入", "", "手动", "manual", "",
                createdAt, clean(note, 500));
        return getWritableDatabase().insertOrThrow("entries", null, values);
    }

    public synchronized long insertManual(long amountFen, String direction, String merchant, String category) {
        return insertManual(amountFen, direction, merchant, category, System.currentTimeMillis(), "");
    }

    /** Editing also confirms an entry because the user supplied its final interpretation. */
    public synchronized void update(long id, long amountFen, String direction, String merchant,
                                    String category, String note) {
        if (exists(getReadableDatabase(), "SELECT 1 FROM entries WHERE id=? AND status<>? AND needs_time_confirmation=1",
                new String[]{Long.toString(id), DELETED})) {
            throw new IllegalStateException("交易日期不明确，请选择日期后再保存");
        }
        updateValues(id, amountFen, direction, merchant, category, note, null);
    }

    /** The supplied time is the date/time the user explicitly chose for this transaction. */
    public synchronized void update(long id, long amountFen, String direction, String merchant,
                                    String category, String note, long transactionAt) {
        validateTimestamp(transactionAt);
        updateValues(id, amountFen, direction, merchant, category, note, transactionAt);
    }

    private void updateValues(long id, long amountFen, String direction, String merchant,
                              String category, String note, Long transactionAt) {
        validate(amountFen, direction);
        ContentValues values = new ContentValues();
        values.put("amount_fen", amountFen);
        values.put("direction", direction);
        values.put("merchant", clean(merchant, 120).isEmpty() ? "手动记录" : clean(merchant, 120));
        values.put("category", clean(category, 60).isEmpty() ? "其他" : clean(category, 60));
        values.put("note", clean(note, 500));
        values.put("status", CONFIRMED);
        values.put("reason", "用户已编辑确认");
        values.put("confidence", 100);
        if (transactionAt != null) {
            values.put("created_at", transactionAt);
            values.put("needs_time_confirmation", 0);
        }
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("entries", values, "id=? AND status<>?", new String[]{Long.toString(id), DELETED});
    }

    public synchronized Totals monthTotals() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Totals totals = totalsInRange(monthRange(today.getYear(), today.getMonthValue()));
        // Preserve the original API: this badge counts the entire review queue, regardless of date.
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE status<>? AND (status=? OR needs_time_confirmation=1)", new String[]{DELETED, PENDING})) {
            if (c.moveToFirst()) totals.pendingCount = c.getInt(0);
        }
        return totals;
    }

    /** Selected-month totals use every confirmed record, independently of list/search limits. */
    public synchronized Totals monthTotals(int year, int month) {
        DateRange range = monthRange(year, month);
        Totals totals = totalsInRange(range);
        // An unknown transaction date cannot safely be assigned to any selected month.
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM entries WHERE status=? AND needs_time_confirmation=0 AND created_at>=? AND created_at<?",
                new String[]{PENDING, Long.toString(range.from), Long.toString(range.until)})) {
            if (c.moveToFirst()) totals.pendingCount = c.getInt(0);
        }
        return totals;
    }

    private Totals totalsInRange(DateRange range) {
        Totals totals = new Totals();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT direction,SUM(amount_fen) FROM entries WHERE status=? AND needs_time_confirmation=0 AND created_at>=? AND created_at<? GROUP BY direction",
                new String[]{CONFIRMED, Long.toString(range.from), Long.toString(range.until)})) {
            while (c.moveToNext()) {
                switch (c.getString(0)) {
                    case RecognitionEngine.EXPENSE: totals.expenseFen = c.getLong(1); break;
                    case RecognitionEngine.INCOME: totals.incomeFen = c.getLong(1); break;
                    case RecognitionEngine.REFUND: totals.refundFen = c.getLong(1); break;
                    case RecognitionEngine.TRANSFER: totals.transferFen = c.getLong(1); break;
                    default: break;
                }
            }
        }
        return totals;
    }

    /** Confirmed monthly category totals, ordered by expense descending then category name. */
    public synchronized List<CategoryTotal> categoryTotals(int year, int month) {
        DateRange range = monthRange(year, month);
        List<CategoryTotal> result = new ArrayList<>();
        String category = "CASE WHEN TRIM(category)='' THEN '其他' ELSE category END";
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + category + " AS category_name,"
                        + "SUM(CASE WHEN direction='expense' THEN amount_fen ELSE 0 END) AS expense_total,"
                        + "SUM(CASE WHEN direction='income' THEN amount_fen ELSE 0 END),"
                        + "SUM(CASE WHEN direction='refund' THEN amount_fen ELSE 0 END),"
                        + "SUM(CASE WHEN direction='transfer' THEN amount_fen ELSE 0 END),COUNT(*)"
                        + " FROM entries WHERE status=? AND needs_time_confirmation=0 AND created_at>=? AND created_at<?"
                        + " GROUP BY " + category + " ORDER BY expense_total DESC,category_name COLLATE NOCASE ASC",
                new String[]{CONFIRMED, Long.toString(range.from), Long.toString(range.until)})) {
            while (c.moveToNext()) {
                CategoryTotal total = new CategoryTotal();
                total.name = c.getString(0);
                total.expenseFen = c.getLong(1);
                total.incomeFen = c.getLong(2);
                total.refundFen = c.getLong(3);
                total.transferFen = c.getLong(4);
                total.entryCount = c.getInt(5);
                result.add(total);
            }
        }
        return result;
    }

    /**
     * Every local calendar day in the month, in ascending order, even when it has no records.
     * Epoch times are grouped through the same ZoneId as the month boundaries, avoiding SQLite's
     * platform-specific localtime handling near timezone and daylight-saving transitions.
     */
    public synchronized List<DayTotal> dayTotals(int year, int month) {
        DateRange range = monthRange(year, month);
        List<DayTotal> result = new ArrayList<>();
        int length = range.start.lengthOfMonth();
        for (int day = 1; day <= length; day++) {
            DayTotal total = new DayTotal();
            total.day = day;
            result.add(total);
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT created_at,direction,amount_fen FROM entries WHERE status=? AND needs_time_confirmation=0 AND created_at>=? AND created_at<?",
                new String[]{CONFIRMED, Long.toString(range.from), Long.toString(range.until)})) {
            while (c.moveToNext()) {
                LocalDate date = Instant.ofEpochMilli(c.getLong(0)).atZone(range.zone).toLocalDate();
                DayTotal total = result.get(date.getDayOfMonth() - 1);
                long amount = c.getLong(2);
                switch (c.getString(1)) {
                    case RecognitionEngine.EXPENSE: total.expenseFen = Math.addExact(total.expenseFen, amount); break;
                    case RecognitionEngine.INCOME: total.incomeFen = Math.addExact(total.incomeFen, amount); break;
                    case RecognitionEngine.REFUND: total.refundFen = Math.addExact(total.refundFen, amount); break;
                    case RecognitionEngine.TRANSFER: total.transferFen = Math.addExact(total.transferFen, amount); break;
                    default: break;
                }
                total.entryCount++;
            }
        }
        return result;
    }

    private static DateRange monthRange(int year, int month) {
        LocalDate start = checkedDate(year, month, 1);
        return new DateRange(start, start.plusMonths(1));
    }

    private static LocalDate checkedDate(int year, int month, int day) {
        if (year < 1970 || year > 9999) throw new IllegalArgumentException("年份应在1970至9999之间");
        return LocalDate.of(year, month, day);
    }

    private static final class DateRange {
        final LocalDate start;
        final ZoneId zone;
        final long from;
        final long until;

        DateRange(LocalDate start, LocalDate until) {
            this.start = start;
            this.zone = ZoneId.systemDefault();
            this.from = start.atStartOfDay(zone).toInstant().toEpochMilli();
            this.until = until.atStartOfDay(zone).toInstant().toEpochMilli();
        }
    }

    public synchronized String exportCsv() {
        StringBuilder out = new StringBuilder("\uFEFF编号,记账时间,类型,金额（元）,商户,分类,状态,来源,识别理由,交易号,备注,首次采集时间,交易日期待核对\r\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        try (Cursor c = getReadableDatabase().query("entries", null, "status<>?", new String[]{DELETED},
                null, null, "created_at ASC,id ASC")) {
            while (c.moveToNext()) {
                Entry e = read(c);
                String[] cells = {Long.toString(e.id), formatter.format(Instant.ofEpochMilli(e.createdAt)),
                        directionLabel(e.direction), money(e.amountFen), e.merchant, e.category,
                        e.needsTimeConfirmation ? "待核对日期" : PENDING.equals(e.status) ? "待确认" : "已入账",
                        e.sourceLabel, e.reason, e.transactionId, e.note,
                        e.capturedAt > 0 ? formatter.format(Instant.ofEpochMilli(e.capturedAt)) : "",
                        e.needsTimeConfirmation ? "是（记账时间为暂存值）" : "否"};
                for (int i = 0; i < cells.length; i++) {
                    if (i > 0) out.append(',');
                    out.append(csv(cells[i]));
                }
                out.append("\r\n");
            }
        }
        return out.toString();
    }

    public static String money(long fen) {
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }

    public static String directionLabel(String direction) {
        if (RecognitionEngine.INCOME.equals(direction)) return "收入";
        if (RecognitionEngine.REFUND.equals(direction)) return "退款";
        if (RecognitionEngine.TRANSFER.equals(direction)) return "转账";
        return "支出";
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        // Prevent merchant or note text from becoming a spreadsheet formula on export.
        int first = 0;
        while (first < text.length()) {
            char ch = text.charAt(first);
            if (!Character.isWhitespace(ch) && !Character.isSpaceChar(ch) && ch != '\uFEFF' && ch != '\u200B') break;
            first++;
        }
        if (first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static ContentValues values(long amountFen, String direction, String merchant, String category,
                                         String status, int confidence, String reason, String sourcePackage,
                                         String sourceLabel, String sourceKind, String transactionId,
                                         long createdAt, String note) {
        ContentValues values = new ContentValues();
        values.put("amount_fen", amountFen);
        values.put("direction", direction);
        values.put("merchant", clean(merchant, 120));
        values.put("category", clean(category, 60));
        values.put("status", status);
        values.put("confidence", confidence);
        values.put("reason", clean(reason, 500));
        values.put("source_package", clean(sourcePackage, 240));
        values.put("source_label", clean(sourceLabel, 120));
        values.put("source_kind", clean(sourceKind, 40));
        values.put("transaction_id", clean(transactionId, 80));
        values.put("created_at", createdAt);
        values.put("updated_at", System.currentTimeMillis());
        values.put("note", clean(note, 500));
        values.put("needs_time_confirmation", 0);
        values.put("captured_at", System.currentTimeMillis());
        return values;
    }

    private static void validate(long amountFen, String direction) {
        if (amountFen <= 0 || amountFen > 999_999_999_99L) throw new IllegalArgumentException("金额应大于0且不超过999999999.99元");
        if (!RecognitionEngine.isDirection(direction)) throw new IllegalArgumentException("无效的收支类型");
    }

    private static void validateTimestamp(long timestamp) {
        if (timestamp <= 0 || timestamp > System.currentTimeMillis() + 300_000L) {
            throw new IllegalArgumentException("请选择有效的交易日期，不能晚于当前时间");
        }
    }

    private static String clean(String value, int limit) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]|[\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
        return result.length() > limit ? result.substring(0, limit) : result;
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : bytes) {
                int value = item & 0xff;
                out.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 15, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Entry read(Cursor c) {
        Entry e = new Entry();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.amountFen = c.getLong(c.getColumnIndexOrThrow("amount_fen"));
        e.direction = c.getString(c.getColumnIndexOrThrow("direction"));
        e.merchant = c.getString(c.getColumnIndexOrThrow("merchant"));
        e.category = c.getString(c.getColumnIndexOrThrow("category"));
        e.status = c.getString(c.getColumnIndexOrThrow("status"));
        e.confidence = c.getInt(c.getColumnIndexOrThrow("confidence"));
        e.reason = c.getString(c.getColumnIndexOrThrow("reason"));
        e.sourcePackage = c.getString(c.getColumnIndexOrThrow("source_package"));
        e.sourceLabel = c.getString(c.getColumnIndexOrThrow("source_label"));
        e.sourceKind = c.getString(c.getColumnIndexOrThrow("source_kind"));
        e.transactionId = c.getString(c.getColumnIndexOrThrow("transaction_id"));
        e.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        e.note = c.getString(c.getColumnIndexOrThrow("note"));
        e.needsTimeConfirmation = c.getInt(c.getColumnIndexOrThrow("needs_time_confirmation")) != 0;
        e.capturedAt = c.getLong(c.getColumnIndexOrThrow("captured_at"));
        return e;
    }
}
