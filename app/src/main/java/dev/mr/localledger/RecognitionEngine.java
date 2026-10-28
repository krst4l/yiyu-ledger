package dev.mr.localledger;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deliberately conservative, local rules; these are not verified against real samples. */
public final class RecognitionEngine {
    public static final String EXPENSE = "expense";
    public static final String INCOME = "income";
    public static final String REFUND = "refund";
    public static final String TRANSFER = "transfer";

    private static final Set<String> PAYMENT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay"));
    private static final Set<String> SHOP_PACKAGES = new HashSet<>(Arrays.asList(
            "com.taobao.taobao", "com.xunmeng.pinduoduo", "com.jingdong.app.mall",
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"));
    private static final String NUMBER = "((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,9})(?:\\.[0-9]{1,2})?)";
    private static final Pattern MONEY = Pattern.compile(
            "(?:[¥￥]|人民币|RMB|CNY)\\s*" + NUMBER
                    + "(?![0-9.,])|(?<![0-9.,-])" + NUMBER + "\\s*(?:元|圆)(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_LABEL = Pattern.compile(
            "(?:实际支付|实付款|实付|付款金额|支付金额|消费金额|扣款金额|交易金额|退款金额|到账金额|收款金额|转账金额)"
                    + "\\s*[:：]?\\s*(?:[¥￥]|人民币|RMB|CNY)?\\s*" + NUMBER + "(?![0-9.,:/-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPENSE_AMOUNT = labelled("实际支付|实付款|实付|付款金额|支付金额|消费金额|扣款金额");
    private static final Pattern REFUND_AMOUNT = labelled("实际退款|退款金额|退回金额");
    private static final Pattern INCOME_AMOUNT = labelled("到账金额|收款金额|收入金额");
    private static final Pattern TRANSFER_AMOUNT = labelled("转账金额|充值金额|提现金额|还款金额|转入金额|转出金额");
    private static final Pattern FOREIGN_CURRENCY = Pattern.compile(
            "美元|港币|港元|日元|欧元|澳元|英镑|新加坡元|HK\\$|US\\$|\\b(?:USD|HKD|JPY|EUR|GBP|AUD|SGD)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE_MONEY = Pattern.compile(
            "(?:[¥￥]|人民币|RMB|CNY)\\s*[-−]|[-−]\\s*(?:[¥￥]|人民币|RMB|CNY)|[-−]\\s*" + NUMBER + "\\s*(?:元|圆)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUCCESS = Pattern.compile(
            "支付成功|付款成功|交易成功|消费成功|扣款成功|已成功支付|成功支付|成功付款|已完成支付");
    private static final Pattern SHOP_SUCCESS = Pattern.compile(
            "支付成功|付款成功|已成功支付|已完成支付|成功付款");
    private static final Pattern REFUND_SUCCESS = Pattern.compile(
            "退款成功|退款已到账|退款到账|退款入账|已退款|已成功退款");
    private static final Pattern INCOME_SUCCESS = Pattern.compile(
            "收款成功|收款到账|收款已到账|已成功收款");
    private static final Pattern TRANSFER_SUCCESS = Pattern.compile(
            "转账成功|转账已到账|成功转账|已转账|转账到账|充值成功|充值到账|成功充值|已充值|提现成功|提现到账|已提现|成功提现|还款成功|已还款|还款入账|成功还款|转入成功|转出成功");
    private static final Pattern INTERNAL_TRANSFER = Pattern.compile(
            "钱包充值|余额充值|信用卡还款|还款金额|提现金额|充值金额|转账金额|转入余额宝|转出余额宝|转入零钱|转出零钱");
    private static final Pattern UNSUCCESSFUL = Pattern.compile(
            "支付失败|付款失败|交易失败|扣款失败|充值失败|提现失败|还款失败|未支付|未付款|待支付|待付款|等待付款|等待支付|待您付款|支付中|付款中|支付处理中|退款申请|申请退款|退款处理中|退款中|转账处理中|充值中|提现处理中|还款处理中|订单已取消|付款已取消|支付已取消|预授权|支付提醒|付款提醒|还款提醒|扣款提醒");
    private static final Pattern ADVERTISEMENT = Pattern.compile(
            "广告|限时抢|抢购|领券|最高可享|红包活动|免单机会|瓜分红包|优惠券到账|支付成功即可|付款成功即可|支付后可得|付款后可得");
    private static final Pattern BANK_EXPENSE = Pattern.compile("消费|支出|扣款|已扣|扣收");
    private static final Pattern BANK_INCOME = Pattern.compile("收入|入账|工资到账|工资发放");
    private static final Pattern NON_PAYMENT_MONEY = Pattern.compile("余额|剩余额度|可用额度|积分|原价|订单总额|优惠金额|优惠券|红包金额|已优惠|节省");
    private static final Pattern TRANSACTION_ID = Pattern.compile(
            "(?:退款单号|转账单号|支付单号|交易单号|交易号)\\s*[:：]?\\s*([A-Za-z0-9]{10,64})(?![A-Za-z0-9])");
    private static final Pattern REFUND_ID = Pattern.compile(
            "退款单号\\s*[:：]?\\s*([A-Za-z0-9]{10,64})(?![A-Za-z0-9])");
    private static final Pattern MERCHANT = Pattern.compile(
            "(?:收款商户|商户名称|商户全称|商家名称|商家全称|收款方|收款人|商户|付款给|付款至|支付给|支付至|付给)"
                    + "[ \\t]*[:：]?[ \\t]*(?:\\r?\\n[ \\t]*)?([^\\n\\r，,；;¥￥]{1,60})");
    private static final Pattern HEADER_AMOUNT = Pattern.compile(
            "^[-−]?[ \\t]*(?:[¥￥][ \\t]*)?" + NUMBER + "[ \\t]*(?:元|圆)?$");
    private static final Pattern HEADER_EXPENSE_SUMMARY = Pattern.compile(
            "^(?:支出|付款金额|支付金额|实际支付|实付款|实付|消费金额|扣款金额)[ :：]*"
                    + "(?:[¥￥][ \\t]*)?" + NUMBER + "[ \\t]*(?:元|圆)$");
    private static final Pattern HEADER_NAME_AMOUNT = Pattern.compile(
            "^(.{1,60}?)(?:[ \\t]+[-−]?[ \\t]*(?:[¥￥][ \\t]*)?"
                    + "|[-−][ \\t]*(?:[¥￥][ \\t]*)?|[¥￥][ \\t]*[-−]?[ \\t]*)"
                    + NUMBER + "[ \\t]*(?:元|圆)?$");
    private static final Pattern HEADER_JOINED_NAME_AMOUNT = Pattern.compile(
            "^(.{0,59}[^0-9.,+\\-−¥￥])" + NUMBER + "[ \\t]*(?:元|圆)$");
    private static final String DATE_TIME = "\\s*[:：]?\\s*([0-9]{4})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})(?:日\\s*|[ T]+)([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?![0-9])";
    private static final Pattern TRANSACTION_TIME = Pattern.compile(
            "(?:付款时间|支付时间|交易时间|转账时间|收款时间|到账时间|充值时间|提现时间|还款时间)" + DATE_TIME);
    private static final Pattern REFUND_TIME = Pattern.compile("(?:退款时间|退回时间|到账时间)" + DATE_TIME);
    private static final Pattern TIME_LABEL = Pattern.compile("付款时间|支付时间|交易时间|退款时间|退回时间|收款时间|到账时间|转账时间|充值时间|提现时间|还款时间");

    private RecognitionEngine() {}

    public static final class Draft {
        public long amountFen;
        public String direction;
        public String merchant;
        public String category;
        /** Heuristic rule score, not a calibrated probability. */
        public int confidence;
        public String reason;
        public String transactionId;
        /** Epoch milliseconds parsed from an explicit transaction-time label, or zero. */
        public long transactionAt;
        public boolean needsTimeConfirmation;
        public String merchantTrace = "";
    }

    /** Fixed type tokens only, never names, amounts, IDs or other captured text. */
    public static String templateShape(PaymentEvent event, Draft draft) {
        if (event == null || !PaymentEvent.ACCESSIBILITY.equals(event.sourceKind)
                || !"com.eg.android.AlipayGphone".equals(event.sourcePackage)) return "";
        String text = Normalizer.normalize(event.headerText.isEmpty() ? event.rawText : event.headerText, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ').replace('\u3000', ' ');
        List<String> tokens = new ArrayList<>();
        if (draft != null && !draft.merchantTrace.isEmpty()) tokens.add(draft.merchantTrace);
        if (!event.headerText.isEmpty()) tokens.add("spatial_header");
        String previous = "";
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String token;
            if (line.matches("账单详情|交易详情")) token = "title";
            else if (line.matches("返回|关闭|更多|完成|帮助|返回按钮")) token = "navigation";
            else if (line.equals("交易成功")) token = "success";
            else if (line.equals("等待确认收货")) token = "awaiting_receipt";
            else if (line.matches("申请退款|退款申请")) token = "refund_action";
            else if (SUCCESS.matcher(line).find()) token = "success_text";
            else if (line.matches("[¥￥]")) token = "currency";
            else if (line.matches("(?:收款商户|商户名称|商户全称|商家名称|商家全称|收款方|收款人|商户)[：:]?")) token = "merchant_label";
            else if (line.matches("(?:付款时间|支付时间)[：:]?")) token = "payment_time_label";
            else if (line.matches("创建时间[：:]?")) token = "created_time_label";
            else if (line.matches("(?:交易时间|收款时间|到账时间)[：:]?")) token = "time_label";
            else if (line.matches("(?:支付宝交易号|交易单号|交易号|订单号|商家订单号)[：:]?")) token = "id_label";
            else if (line.matches("(?:付款方式|支付方式)[：:]?")) token = "payment_method_label";
            else if (line.matches("(?:实际支付|实付款|实付|付款金额|支付金额|消费金额|扣款金额)[：:]?")) token = "paid_amount_label";
            else if (line.matches("(?:商品说明|交易状态|服务费|交易金额|余额)[：:]?")) token = "field_label";
            else if (line.matches("[0-9]{4}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}.*")) token = "time_value";
            else if (HEADER_EXPENSE_SUMMARY.matcher(line).matches()) token = "expense_amount_summary";
            else if (HEADER_AMOUNT.matcher(line).matches()) token = line.matches("^[-−].*") ? "amount_signed" : "amount";
            else if (line.matches("[A-Za-z0-9]{10,64}") && line.matches(".*[0-9].*")) token = "identifier";
            else token = isMerchantName(line) ? "name" : "other";
            tokens.add(token);
            if (token.equals("name") && previous.matches("账单详情|交易详情") && tokens.size() < 20) {
                tokens.add("header_begin");
                String lastClass = "";
                int classes = 0;
                for (int offset = 0; offset < line.length() && classes < 24; ) {
                    int point = line.codePointAt(offset);
                    offset += Character.charCount(point);
                    String kind = Character.isLetter(point) ? "header_letter" : Character.isDigit(point) ? "header_digit"
                            : Character.isWhitespace(point) ? "header_space"
                            : point == '-' || point == '−' ? "header_minus"
                            : point == '¥' || point == '￥' ? "header_currency"
                            : point == '.' ? "header_dot" : point == ',' || point == '，' ? "header_comma"
                            : point == ':' || point == '：' ? "header_colon" : point == '/' ? "header_slash"
                            : point == '(' || point == '（' ? "header_left" : point == ')' || point == '）' ? "header_right" : "header_other";
                    if (!kind.equals(lastClass)) { tokens.add(kind); classes++; lastClass = kind; }
                }
                tokens.add("header_end");
            }
            previous = line;
            if (tokens.size() >= 48) break;
        }
        return String.join(",", tokens);
    }

    public static Draft parse(PaymentEvent event) {
        return parse(event, null);
    }

    static Draft parse(PaymentEvent event, Draft diagnostic) {
        if (event == null || event.truncated || event.sourcePackage.isEmpty() || event.rawText.isEmpty())
            return rejected(diagnostic, "parse_missing_data");
        boolean bank = PaymentEvent.BANK_NOTIFICATION.equals(event.sourceKind);
        boolean shop = SHOP_PACKAGES.contains(event.sourcePackage);
        if (!bank && !shop && !PAYMENT_PACKAGES.contains(event.sourcePackage)) return rejected(diagnostic, "parse_source");
        if (!bank && !PaymentEvent.NOTIFICATION.equals(event.sourceKind)
                && !PaymentEvent.ACCESSIBILITY.equals(event.sourceKind)) return rejected(diagnostic, "parse_source");
        String text = Normalizer.normalize(event.rawText, Normalizer.Form.NFKC).replace('\u00a0', ' ').replace('\u3000', ' ')
                .replaceAll("[\\t ]+", " ").trim();
        // The app package alone is insufficient: wallet apps also deliver ordinary chat messages.
        // Capture collectors place the notification title on the first line.
        if (!bank && !shop && PaymentEvent.NOTIFICATION.equals(event.sourceKind)
                && !acceptsNotificationTitle(event.sourcePackage, text.split("\\r?\\n", 2)[0].trim())) return rejected(diagnostic, "parse_notification_title");
        if (UNSUCCESSFUL.matcher(text).find()) return rejected(diagnostic, "parse_unpaid_or_failed");
        if (ADVERTISEMENT.matcher(text).find()) return rejected(diagnostic, "parse_advertisement");
        // This first version understands positive CNY amounts only; never silently reinterpret forex.
        if (FOREIGN_CURRENCY.matcher(text).find()) return rejected(diagnostic, "parse_foreign");
        if (NEGATIVE_MONEY.matcher(text).find()) return rejected(diagnostic, "parse_signed_amount");

        String direction;
        boolean explicit;
        if (REFUND_SUCCESS.matcher(text).find()) {
            direction = REFUND;
            explicit = true;
        } else if (TRANSFER_SUCCESS.matcher(text).find()) {
            direction = TRANSFER;
            explicit = true;
        } else if (INCOME_SUCCESS.matcher(text).find()) {
            direction = INTERNAL_TRANSFER.matcher(text).find() ? TRANSFER : INCOME;
            explicit = true;
        } else if ((shop ? SHOP_SUCCESS : SUCCESS).matcher(text).find()) {
            direction = INTERNAL_TRANSFER.matcher(text).find() ? TRANSFER : EXPENSE;
            explicit = true;
        } else if (!shop && !bank && (text.contains("支付凭证") || text.contains("付款凭证")
                || text.contains("已支付") || text.contains("已付款"))
                && AMOUNT_LABEL.matcher(text).find()) {
            direction = INTERNAL_TRANSFER.matcher(text).find() ? TRANSFER : EXPENSE;
            explicit = false;
        } else if (bank && BANK_EXPENSE.matcher(text).find()) {
            direction = text.contains("转账") || text.contains("还款") || INTERNAL_TRANSFER.matcher(text).find()
                    ? TRANSFER : EXPENSE;
            explicit = false;
        } else if (bank && BANK_INCOME.matcher(text).find()) {
            direction = text.contains("转账") || text.contains("还款") || INTERNAL_TRANSFER.matcher(text).find()
                    ? TRANSFER : INCOME;
            explicit = false;
        } else {
            return rejected(diagnostic, "parse_status");
        }

        // Shopping notices require an actual payment/refund/transfer result, not an order amount.
        if (shop && !SHOP_SUCCESS.matcher(text).find() && !REFUND_SUCCESS.matcher(text).find()) return rejected(diagnostic, "parse_shop_status");
        Pattern preferred = REFUND.equals(direction) ? REFUND_AMOUNT
                : INCOME.equals(direction) ? INCOME_AMOUNT
                : TRANSFER.equals(direction) ? TRANSFER_AMOUNT : EXPENSE_AMOUNT;
        List<Long> amounts = amounts(preferred, text);
        boolean directionAmountMissing = amounts.isEmpty() && !EXPENSE.equals(direction);
        if (amounts.isEmpty()) amounts = amounts(AMOUNT_LABEL, text);
        boolean labelledAmount = !amounts.isEmpty();
        if (amounts.isEmpty()) amounts = amounts(MONEY, text);
        if (amounts.isEmpty()) return rejected(diagnostic, "parse_amount");
        long amount = amounts.get(0);
        boolean ambiguous = false;
        for (long candidate : amounts) if (candidate != amount) ambiguous = true;

        Draft result = new Draft();
        result.amountFen = amount;
        result.direction = direction;
        result.merchant = merchant(text, event, amount, direction, result);
        if (diagnostic != null) diagnostic.merchantTrace = result.merchantTrace;
        // A refund may quote the original payment ID; partial refunds can share that original ID.
        result.transactionId = match(REFUND.equals(direction) ? REFUND_ID : TRANSACTION_ID, text);
        result.category = category(result.merchant, direction);
        result.transactionAt = transactionAt(REFUND.equals(direction) ? REFUND_TIME : TRANSACTION_TIME, text);
        result.needsTimeConfirmation = event.invalidTimestamp
                || (result.transactionAt == 0 && (PaymentEvent.ACCESSIBILITY.equals(event.sourceKind)
                    || TIME_LABEL.matcher(text).find()));
        // Banks and shopping apps remain review-only until representative samples are supplied.
        result.confidence = explicit ? 88 : 65;
        if (labelledAmount && explicit) result.confidence += 4;
        boolean amountNeedsContext = !labelledAmount && "待补充商户".equals(result.merchant);
        if (amountNeedsContext) result.confidence = Math.min(result.confidence, 80);
        if (bank) result.confidence = Math.min(result.confidence, 78);
        if (shop) result.confidence = Math.min(result.confidence, 80);
        boolean wechatNotification = "com.tencent.mm".equals(event.sourcePackage)
                && PaymentEvent.NOTIFICATION.equals(event.sourceKind);
        if (wechatNotification) result.confidence = Math.min(result.confidence, 80);
        if (PaymentEvent.ACCESSIBILITY.equals(event.sourceKind)) {
            result.confidence = Math.min(result.confidence, 84);
        }
        if (ambiguous) result.confidence = Math.min(result.confidence, 45);
        if (directionAmountMissing) result.confidence = Math.min(result.confidence, 70);
        if (result.needsTimeConfirmation) result.confidence = Math.min(result.confidence, 70);
        boolean delayedTime = result.transactionAt > 0 && Math.abs(result.transactionAt - event.observedAt) > 86_400_000L;
        if (delayedTime) result.confidence = Math.min(result.confidence, 75);
        result.reason = ambiguous ? "页面包含不同金额，已取首个候选金额，必须核对"
                : bank ? "用户选定的银行通知，规则未经真实样本验证，请核对"
                : shop ? "识别到电商付款结果，规则未经真实样本验证，请核对"
                : PaymentEvent.ACCESSIBILITY.equals(event.sourceKind)
                    ? "支付结果页候选，仅作待确认记录"
                    : explicit ? "通知含明确交易结果及人民币金额，基于本地保守规则"
                    : "支付凭证金额候选，基于本地保守规则";
        if (!explicit) result.reason += "；缺少明确完成状态，必须核对";
        if (wechatNotification) result.reason += "；微信通知标题仅为模板筛选，不能认证付款来源，必须核对";
        if (amountNeedsContext) result.reason += "；金额缺少明确字段或商户信息，必须核对";
        if (directionAmountMissing) result.reason += "；缺少该交易类型专属金额字段，请核对候选金额";
        if (event.invalidTimestamp) result.reason += "；来源时间无效，暂用采集时间，请核对";
        if (result.needsTimeConfirmation) result.reason += "；交易日期不明确，编辑日期后才可入账";
        if (delayedTime) result.reason += "；交易时间与通知时间相差较大，请核对历史记录";
        if (TRANSFER.equals(direction)) {
            result.confidence = Math.min(result.confidence, 80);
            result.reason += "；转账不计入消费支出";
        }
        return result;
    }

    private static Draft rejected(Draft diagnostic, String reason) {
        if (diagnostic != null) diagnostic.merchantTrace = reason;
        return null;
    }

    private static Pattern labelled(String labels) {
        return Pattern.compile("(?:" + labels + ")\\s*[:：]?\\s*(?:[¥￥]|人民币|RMB|CNY)?\\s*"
                + NUMBER + "(?![0-9.,:/-])", Pattern.CASE_INSENSITIVE);
    }

    private static long transactionAt(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        long result = 0;
        ZoneId zone = ZoneId.systemDefault();
        while (matcher.find()) {
            try {
                LocalDateTime time = LocalDateTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                        matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6)));
                // An ambiguous or nonexistent local time also needs the user's decision.
                if (zone.getRules().getValidOffsets(time).size() != 1) return 0;
                long candidate = time.atZone(zone).toInstant().toEpochMilli();
                if (candidate <= 0 || candidate > System.currentTimeMillis() + 300_000L) return 0;
                if (result != 0 && candidate != result) return 0;
                result = candidate;
            } catch (DateTimeException | NumberFormatException invalid) {
                return 0;
            }
        }
        return result;
    }

    private static List<Long> amounts(Pattern pattern, String text) {
        List<Long> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && values.size() < 24) {
            if (pattern == MONEY) {
                int lineStart = text.lastIndexOf('\n', matcher.start());
                String prefix = text.substring(Math.max(lineStart + 1, matcher.start() - 16), matcher.start());
                if (NON_PAYMENT_MONEY.matcher(prefix).find()) continue;
            }
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String number = matcher.group(group);
                if (number == null) continue;
                try {
                    long fen = new BigDecimal(number.replace(",", "")).movePointRight(2).longValueExact();
                    if (fen > 0 && fen <= 999_999_999_99L) values.add(fen);
                } catch (NumberFormatException | ArithmeticException ignored) {
                    // Invalid amounts are discarded without retaining the source text.
                }
            }
        }
        return values;
    }

    /** A template filter, not authentication of an official account or notification. */
    public static boolean acceptsNotificationTitle(String packageName, String title) {
        if (packageName == null || title == null) return false;
        title = Normalizer.normalize(title, Normalizer.Form.NFKC).trim();
        if ("com.tencent.mm".equals(packageName)) {
            return title.matches("(?:微信支付|微信收款助手|微信收款商业版|收款助手)(?:[（(][^）)]{0,24}[）)])?");
        }
        if ("com.eg.android.AlipayGphone".equals(packageName)) {
            return title.matches("(?:支付宝|支付宝通知|支付助手|支付成功|付款成功|收钱码|收款到账|商家服务|转账到账|到账通知|收款通知|花呗|退款通知)(?:[（(][^）)]{0,24}[）)])?");
        }
        return "com.unionpay".equals(packageName)
                && title.matches("(?:云闪付|云闪付通知|支付成功|付款成功|交易通知|交易成功|收款通知|退款通知)(?:[（(][^）)]{0,24}[）)])?");
    }

    private static String merchant(String text, PaymentEvent event, long amountFen, String direction, Draft diagnostic) {
        String candidate = match(MERCHANT, text);
        candidate = candidate.replaceAll("(?:支付金额|付款金额|交易金额|实付|订单号|交易号|付款时间|交易时间).*$", "").trim();
        if (isMerchantName(candidate)) { diagnostic.merchantTrace = "merchant_labeled"; return candidate; }
        // Alipay's completed expense page can place the name above the amount, without a label.
        // Require the observed three-line header and the already selected exact amount; never
        // infer a merchant from a nearby balance, item description or an unrelated free-text line.
        if (!"com.eg.android.AlipayGphone".equals(event.sourcePackage)
                || !PaymentEvent.ACCESSIBILITY.equals(event.sourceKind) || !EXPENSE.equals(direction)) {
            diagnostic.merchantTrace = "merchant_not_applicable";
            return "待补充商户";
        }
        List<String> lines = new ArrayList<>();
        String headerText = event.headerText.isEmpty() ? text
                : Normalizer.normalize(event.headerText, Normalizer.Form.NFKC)
                    .replace('\u00a0', ' ').replace('\u3000', ' ').replaceAll("[\\t ]+", " ");
        for (String line : headerText.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
            if (lines.size() >= 24) break;
        }
        String headerName = "";
        diagnostic.merchantTrace = "header_not_found";
        for (int i = 1; i + 1 < lines.size(); i++) {
            if (!"交易成功".equals(lines.get(i + 1))) continue;
            String name;
            Matcher amount = HEADER_AMOUNT.matcher(lines.get(i));
            if (!amount.matches()) amount = HEADER_EXPENSE_SUMMARY.matcher(lines.get(i));
            if (amount.matches()) {
                if (!sameAmount(amount.group(1), amountFen)) { diagnostic.merchantTrace = "header_amount_mismatch"; continue; }
                name = lines.get(i - 1);
                if (name.matches("[¥￥]") && i >= 2) name = lines.get(i - 2);
            } else if (lines.get(i - 1).matches("账单详情|交易详情")) {
                // Some pages omit the visual amount as a separate accessibility text node.
                // The observed accessible header is then title / name / success. The amount
                // has already passed the ordinary payment rules elsewhere on this same page.
                name = lines.get(i);
                Matcher combined = HEADER_NAME_AMOUNT.matcher(name);
                if (!combined.matches()) combined = HEADER_JOINED_NAME_AMOUNT.matcher(name);
                if (combined.matches()) {
                    if (!sameAmount(combined.group(2), amountFen)) { diagnostic.merchantTrace = "header_amount_mismatch"; continue; }
                    name = combined.group(1).trim();
                } else if (MONEY.matcher(name).find() || name.matches(".*[0-9]+\\.[0-9]{1,2}.*")) {
                    diagnostic.merchantTrace = "header_embedded_amount"; continue;
                }
            } else continue;
            if (!isMerchantName(name)) {
                diagnostic.merchantTrace = name.matches("支出|付款金额|支付金额|实际支付|实付款|实付|消费金额|扣款金额")
                        ? "header_name_is_label" : "header_invalid_name";
                continue;
            }
            if (MONEY.matcher(name).find() || name.matches(".*[0-9]+\\.[0-9]{1,2}.*")) {
                diagnostic.merchantTrace = "header_embedded_amount";
                continue;
            }
            if (!headerName.isEmpty() && !headerName.equals(name)) {
                diagnostic.merchantTrace = "header_conflict"; return "待补充商户";
            }
            headerName = name;
        }
        if (!headerName.isEmpty()) diagnostic.merchantTrace = "header_found";
        return headerName.isEmpty() ? "待补充商户" : headerName;
    }

    private static boolean sameAmount(String value, long amountFen) {
        try { return new BigDecimal(value.replace(",", "")).movePointRight(2).longValueExact() == amountFen; }
        catch (NumberFormatException | ArithmeticException invalid) { return false; }
    }

    private static boolean isMerchantName(String value) {
        if (value.isEmpty() || value.length() > 60 || "待补充商户".equals(value)
                || value.matches(".*[\\p{Cntrl}].*") || !value.matches(".*\\p{L}.*")
                || value.matches(".*[0-9]{8,}.*")) return false;
        if (value.matches("返回|关闭|更多|账单详情|交易详情|交易成功|等待确认收货|支付成功|付款成功|支付宝|微信支付|支出|收入|退款|转账")) return false;
        return !value.matches("(?:交易状态|交易时间|支付时间|付款时间|创建时间|付款方式|支付方式|商品说明|交易单号|订单号|商家订单号|对方账户|收款方|收款人|商户名称|商户全称|商家名称|商家全称|交易金额|付款金额|支付金额|实际支付|实付款|实付|总计|合计|消费金额|扣款金额|服务费|余额)[：:]?.*");
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    public static String category(String merchant, String direction) {
        if (REFUND.equals(direction)) return "退款";
        if (TRANSFER.equals(direction)) return "转账";
        if (INCOME.equals(direction)) return "收入";
        String name = merchant == null ? "" : merchant.toLowerCase(Locale.ROOT);
        if (name.matches(".*(餐厅|餐饮|饭店|饭馆|面馆|小吃|咖啡|奶茶|茶饮|汉堡|外卖|火锅|肯德基|麦当劳|瑞幸|星巴克).*")) return "餐饮";
        if (name.matches(".*(地铁|公交|出租|滴滴|停车|加油|铁路|航空).*")) return "交通";
        if (name.matches(".*(超市|便利店|商场|百货|淘宝|京东|拼多多|商店).*")) return "购物";
        if (name.matches(".*(医院|药房|药店|诊所|医疗).*")) return "医疗";
        if (name.matches(".*(电影|影院|游戏|乐园|演出|健身).*")) return "娱乐";
        if (name.matches(".*(水费|电费|燃气|通信|移动|联通|电信|宽带|物业).*")) return "生活缴费";
        return "其他";
    }

    public static boolean isDirection(String value) {
        return EXPENSE.equals(value) || INCOME.equals(value) || REFUND.equals(value) || TRANSFER.equals(value);
    }
}
