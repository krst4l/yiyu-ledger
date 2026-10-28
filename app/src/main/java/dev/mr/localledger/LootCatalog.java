package dev.mr.localledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A dated, non-official article snapshot, not a current or exhaustive game catalogue.
 * RMB references are optional local visual equivalents, not official prices or exchange rates.
 */
public final class LootCatalog {
    public static final String SOURCE_URL = "https://gl.ali213.net/html/2025-12/1726225.html";
    public static final String SOURCE_DATE = "2025-12-12";

    public static final class Item {
        public final String id;
        public final String name;
        public final String kind;
        /** Historical value in the source article; zero means the source gives no value. */
        public final long historicalGameValue;
        public final long defaultFen;
        public final String unit;

        private Item(String id, String name, String kind, long historicalGameValue, String unit) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.historicalGameValue = historicalGameValue;
            // One historical watch is anchored to RMB 20 only as a default visual proportion.
            this.defaultFen = historicalGameValue <= 0 ? 0 : BigDecimal.valueOf(historicalGameValue)
                    .multiply(BigDecimal.valueOf(2_000L))
                    .divide(BigDecimal.valueOf(212_624L), 0, RoundingMode.HALF_UP).longValueExact();
            this.unit = unit;
        }
    }

    public static final class Match {
        public final Item item;
        public final long referenceFen;
        /** Decimal count without a grouping separator, or "<0.01" for very small amounts. */
        public final String quantity;
        /** A quantity with its object, such as "1只怀表". */
        public final String quantityLabel;

        private Match(Item item, long referenceFen, long amountFen) {
            this.item = item;
            this.referenceFen = referenceFen;
            BigDecimal ratio = BigDecimal.valueOf(amountFen)
                    .divide(BigDecimal.valueOf(referenceFen), 2, RoundingMode.HALF_UP);
            quantity = ratio.signum() == 0 ? "<0.01" : ratio.stripTrailingZeros().toPlainString();
            quantityLabel = quantity + item.unit + item.name;
        }

        /** Keep the approximation explicit whenever the equivalent is presented. */
        public String format() { return "≈ " + quantityLabel; }
    }

    private static final List<Item> ITEMS = Collections.unmodifiableList(Arrays.asList(
            new Item("item_01", "黄金瞪羚", "gold", 426740L, "个"),
            new Item("item_02", "强化碳纤维板", "default", 2358835L, "个"),
            new Item("item_03", "火箭燃料", "case", 0L, "个"),
            new Item("item_04", "摄影机", "case", 613315L, "台"),
            new Item("item_05", "显卡", "default", 328721L, "个"),
            new Item("item_06", "军用无人机", "default", 526841L, "个"),
            new Item("item_07", "军用电台", "case", 884166L, "台"),
            new Item("item_08", "笔记本电脑", "case", 2132861L, "台"),
            new Item("item_09", "刀片服务器", "case", 1417231L, "台"),
            new Item("item_10", "高速磁盘阵列", "disk", 1716097L, "个"),
            new Item("item_11", "G.T.I卫星通信天线", "default", 0L, "个"),
            new Item("item_12", "飞行记录仪", "case", 1730039L, "个"),
            new Item("item_13", "军用炮弹", "default", 1467594L, "个"),
            new Item("item_14", "军用控制终端", "case", 383098L, "台"),
            new Item("item_15", "克劳迪乌斯半身像", "default", 1263095L, "个"),
            new Item("item_16", "呼吸机", "case", 442423L, "台"),
            new Item("item_17", "奥莉薇娅香槟", "default", 334142L, "瓶"),
            new Item("item_18", "实验数据", "disk", 225872L, "份"),
            new Item("item_19", "量子存储", "disk", 281255L, "个"),
            new Item("item_20", "棘龙爪化石", "default", 343359L, "个"),
            new Item("item_21", "滑膛枪展品", "default", 638559L, "件"),
            new Item("item_22", "非洲之心", "diamond", 12757768L, "个"),
            new Item("gold", "万足金条", "gold", 327272L, "根"),
            new Item("watch", "赛伊德的怀表", "watch", 212624L, "只"),
            new Item("item_25", "曼德尔超算单元", "case", 1988569L, "个"),
            new Item("item_26", "便携军用雷达", "case", 2025956L, "台"),
            new Item("item_27", "军用信息终端", "case", 861268L, "台"),
            new Item("item_28", "主战坦克模型", "default", 2117465L, "个"),
            new Item("item_29", "步战车模型", "default", 1297488L, "个"),
            new Item("item_30", "名贵机械表", "watch", 208139L, "只"),
            new Item("item_31", "绝密服务器", "case", 2104986L, "台"),
            new Item("item_32", "自动体外除颤器", "case", 1376331L, "台"),
            new Item("item_33", "云存储阵列", "disk", 1415192L, "个"),
            new Item("item_34", "扫拖一体机器人", "case", 2024426L, "台"),
            new Item("item_35", "强力吸尘器", "case", 1514571L, "台"),
            new Item("item_36", "高级咖啡豆", "default", 0L, "份"),
            new Item("item_37", "复苏呼吸机", "case", 7201394L, "台"),
            new Item("item_38", "微型反应炉", "case", 4401960L, "台"),
            new Item("item_39", "装甲车电池", "case", 6150202L, "个"),
            new Item("item_40", "动力电池组", "case", 1908840L, "个"),
            new Item("item_41", "医疗机械人", "case", 1265869L, "台"),
            new Item("item_42", "雷斯的留声机", "case", 1251370L, "台"),
            new Item("item_43", "万金泪冠", "crown", 2613217L, "个"),
            new Item("item_44", "“天圆地方”", "default", 499808L, "个"),
            new Item("item_45", "“纵横”", "default", 2618723L, "个"),
            new Item("item_46", "ECMO", "case", 2610827L, "台"),
            new Item("item_47", "“钻石”鱼子酱", "default", 172170L, "份"),
            new Item("item_48", "海洋之泪", "diamond", 25548604L, "个"),
            new Item("item_49", "浮力补偿设备", "default", 0L, "个"),
            new Item("item_50", "潮汐监狱地图-4", "default", 305558L, "份"),
            new Item("item_51", "Alpha脑机实验数据-渡鸦", "disk", 178147L, "份"),
            new Item("item_52", "印象派名画", "default", 2055651L, "幅"),
            new Item("item_53", "定位接收器", "case", 181048L, "个"),
            new Item("item_54", "已封存音源", "disk", 492170L, "份"),
            new Item("item_55", "“雷明顿”打字机", "case", 0L, "台"),
            new Item("item_56", "电子脚镣", "default", 289108L, "个"),
            new Item("item_57", "试制聚变供能单元", "case", 2489420L, "个"),
            new Item("item_58", "高能瓦斯罐", "case", 519703L, "个"),
            new Item("item_59", "超声波切割刀", "default", 196481L, "把"),
            new Item("item_60", "名窑瓷器", "default", 1463706L, "个"),
            new Item("item_61", "黄金鳄鱼头雕像", "gold", 509063L, "个"),
            new Item("item_62", "乙巳玄武", "default", 0L, "个"),
            new Item("item_63", "炫彩麦小蛋", "default", 0L, "个"),
            new Item("item_64", "幸运木雕", "default", 0L, "个"),
            new Item("item_65", "吴彦祖之镜", "default", 0L, "个"),
            new Item("item_66", "鎏金卡牌", "disk", 193362L, "张"),
            new Item("item_67", "炫彩克小圈", "default", 0L, "个"),
            new Item("item_68", "手办-阿米娅（近卫）", "default", 0L, "个"),
            new Item("item_69", "至纯源石", "diamond", 0L, "个"),
            new Item("item_70", "炫彩兰小登", "default", 0L, "个"),
            new Item("item_71", "比利狼", "default", 0L, "个"),
            new Item("item_72", "黄金鸟窝", "gold", 0L, "个"),
            new Item("item_73", "黄金鸟蛋", "gold", 0L, "个"),
            new Item("item_74", "炫彩鸟蛋", "default", 0L, "个"),
            new Item("item_75", "炫彩拉小宅", "default", 0L, "个"),
            new Item("item_76", "烽火世界杯纪念奖杯", "crown", 0L, "个"),
            new Item("item_77", "阿萨拉极品平安果", "default", 0L, "个"),
            new Item("item_78", "渡鸦的录音数据-1", "disk", 0L, "份")
    ));

    private LootCatalog() {}

    public static List<Item> items() { return ITEMS; }

    /** Count references available for matching, regardless of whether their display is enabled. */
    public static int configuredCount(LedgerPreferences prefs) {
        if (prefs == null) return 0;
        int count = 0;
        for (Item item : ITEMS) if (prefs.lootReferenceFen(item.id, item.defaultFen) > 0) count++;
        return count;
    }

    /** Pick the highest configured reference covered by the amount, or the smallest reference. */
    public static Match match(long amountFen, LedgerPreferences prefs) {
        if (amountFen <= 0 || prefs == null || !prefs.lootEnabled()) return null;
        Item smallest = null;
        long smallestFen = Long.MAX_VALUE;
        Item covered = null;
        long coveredFen = 0;
        for (Item item : ITEMS) {
            long referenceFen = prefs.lootReferenceFen(item.id, item.defaultFen);
            if (referenceFen <= 0) continue;
            if (referenceFen < smallestFen) {
                smallest = item;
                smallestFen = referenceFen;
            }
            if (referenceFen <= amountFen && referenceFen > coveredFen) {
                covered = item;
                coveredFen = referenceFen;
            }
        }
        return covered != null ? new Match(covered, coveredFen, amountFen)
                : smallest == null ? null : new Match(smallest, smallestFen, amountFen);
    }
}
