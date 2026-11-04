package com.snowy.balancehud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BalanceHudClient implements ClientModInitializer {
    // ===== EDIT THESE =====
    static final String NAME = "2amsnowy";
    static final double RAKE_TARGET = 1e9;
    static final String RAKE_LABEL = "$1B";
    static final double RAKE_PCT = 0.10;          // rakeback = 10% of what they lost...
    static final String RAKE_RULE = "10% over $1B lost";   // rakeback = RAKE_PCT of losses once they've lost at least RAKE_TARGET
    // ======================

    static final int[] BAL = {8, 8}, BAL_D = {8, 8};
    static final int[] PROFIT_POS = {-1, 8}, PROFIT_D = {-1, 8};   // x = -1 means "stick to the right edge"
    static final int[] RAKE_POS = {8, 68}, RAKE_D = {8, 68};
    static final Map<String, Double> NETS = new LinkedHashMap<>();
    static double PROFIT_BASE = 0;                                    // set by /profit reset: the profit shown = In - Out - this
    static double IN_TOTAL = 0, OUT_TOTAL = 0;                       // money paid to you / paid out by you
    static final List<Double> HISTORY = new ArrayList<>();           // running profit after each payment
    record Bet(String name, double amount, long ts) {}                  // amount: + = they paid you (they lost), - = you paid them (they won)
    static final List<Bet> RECENT = new ArrayList<>();                // newest first
    static final Map<String, Double> RAKE_BASE = new LinkedHashMap<>();   // loss already "paid out" per player (set by /rake)

    // auctions: viewers "bid" by paying you; the biggest single payment while the timer runs wins
    record Auc(String item, String winner, double amount, long ts) {}
    static final List<Auc> AUC_LOG = new ArrayList<>();              // finished auctions, newest first
    static boolean aucActive = false;
    static String aucItem = "";
    static long aucEnd = 0, aucDur = 60_000, aucDefault = 60_000;
    static String aucTopName = null;
    static double aucTopAmt = 0;
    static final int[] AUC_POS = {196, 8}, AUC_D = {196, 8};

    // DonutSMP payment messages (a few wordings are accepted)
    static final String NAME_RE = "(?<name>[.\\w]{1,17})";
    static final String AMT_RE = "\\$[\\s\\u00A0]*(?<num>[\\d,]+(?:\\.\\d+)?)\\s*(?<suf>[kKmMbBtT])?";
    static final String PRE = "^(?:\\[[^\\]]*\\]\\s*|[^\\p{L}\\p{N}.])*";
    static Pattern rx(String s) {
        return Pattern.compile(s.replace(" ", "[\\s\\u00A0]+"));
    }

    static final Pattern[] IN = {
        rx(PRE + NAME_RE + " paid you " + AMT_RE),
        rx(PRE + NAME_RE + " sent you " + AMT_RE),
        rx(PRE + "You received " + AMT_RE + " from " + NAME_RE)
    };
    static final Pattern[] OUT = {
        rx(PRE + "You paid " + NAME_RE + " " + AMT_RE),
        rx(PRE + "You sent " + AMT_RE + " to " + NAME_RE)
    };

    static final Pattern MONEY = Pattern.compile("\\$[\\s\\u00A0]*([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT])?");
    static float SCALE = 0.65f;   // overall size of all panels (change in game with /hudscale 0.5 ... 1.5)

    static int sw() {
        return (int) (Minecraft.getInstance().getWindow().getGuiScaledWidth() / SCALE);
    }

    static double balance = -1, start = -1;
    static boolean awaiting = false;
    static long requestedAt = 0;

    static final int TITLE = 0xFFE9A8D8, WHITE = 0xFFF2F3F7, MUTED = 0xFF9AA0AD, GREEN = 0xFF3DF08A, RED = 0xFFFF4D6D, PINK = 0xFFFF3D8B;

    @Override
    public void onInitializeClient() {
        load();

        // Commands typed by you (never sent to the server):
        //  /auclog start 60 Item | end | time 90 | clear   /auchud x y
        //  /rake remove Name | clear   (resets only rakeback)
        //  /profit [profit|bets|nets|auto|rake|auction|balance|hide|show|reset|clear]
        //  /hudscale 0.75, /balhud, /profithud, /rakehud   <x> <y> | left|right|up|down [amt] | reset
        //  /nets <name> <amount> | set <name> <amount> | remove <name> | clear
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            String[] a = cmd.trim().split("\\s+");
            try {
                switch (a[0].toLowerCase()) {
                    case "balhud" -> move(BAL, BAL_D, a);
                    case "profithud" -> move(PROFIT_POS, PROFIT_D, a);
                    case "profitpage" -> pinned = a.length > 1 && a[1].matches("[1-3]") ? Integer.parseInt(a[1]) - 1 : -1;
                    case "rakehud" -> move(RAKE_POS, RAKE_D, a);
                    case "rake" -> rakeCmd(a);
                    case "auchud" -> move(AUC_POS, AUC_D, a);
                    case "profit" -> {
                        if (a.length < 2) {
                            profitHidden = !profitHidden;
                        } else {
                            switch (a[1].toLowerCase()) {
                                case "reset" -> { PROFIT_BASE = IN_TOTAL - OUT_TOTAL; HISTORY.clear(); }
                                case "clear", "resetall" -> { IN_TOTAL = 0; OUT_TOTAL = 0; PROFIT_BASE = 0; HISTORY.clear(); }
                                case "profit" -> { pinned = 0; profitHidden = false; }
                                case "bets" -> { pinned = 1; profitHidden = false; }
                                case "nets" -> { pinned = 2; profitHidden = false; }
                                case "auto" -> { pinned = -1; profitHidden = false; }
                                case "rake" -> rakeHidden = !rakeHidden;
                                case "auction" -> aucHidden = !aucHidden;
                                case "balance" -> balHidden = !balHidden;
                                case "hide" -> { balHidden = true; profitHidden = true; rakeHidden = true; aucHidden = true; }
                                case "show" -> { balHidden = false; profitHidden = false; rakeHidden = false; aucHidden = false; }
                                default -> { return true; }
                            }
                        }
                    }
                    default -> { return true; }
                }
                save();
            } catch (Exception ignored) { }
            return false;
        });

        ClientPlayConnectionEvents.JOIN.register((h, sn, c) -> { balance = -1; start = -1; });

        // When YOU run /bal (or /balance), read the reply and update the balance box.
        ClientSendMessageEvents.COMMAND.register(cmd -> {
            String c = cmd.trim().toLowerCase();
            if (c.equals("bal") || c.equals("balance")) {
                awaiting = true;
                requestedAt = System.currentTimeMillis();
            }
        });

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            String text = msg.getString();
            if (!overlay && trackPay(text)) return;
            if (!overlay && awaiting) {
                if (System.currentTimeMillis() - requestedAt > 5000) {
                    awaiting = false;
                } else {
                    Double v = parse(text);
                    if (v != null) {
                        balance = v;
                        if (start < 0) start = v;
                        awaiting = false;
                        return;
                    }
                }
            }
            if (text.contains("$")) logLine((overlay ? "[bar] " : "[chat] ") + text);
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("balancehud", "hud"), (g, dt) -> render(g));
    }

    // Player pays you = they bet (their net goes down). You pay them = they win (their net goes up).
    static boolean trackPay(String text) {
        String t = text.trim();
        Matcher m = null;
        boolean in = true;
        for (Pattern p : IN) {
            Matcher x = p.matcher(t);
            if (x.find()) { m = x; break; }
        }
        if (m == null) {
            in = false;
            for (Pattern p : OUT) {
                Matcher x = p.matcher(t);
                if (x.find()) { m = x; break; }
            }
        }
        if (m == null) return false;
        Double v = amount(m.group("num") + (m.group("suf") == null ? "" : m.group("suf")));
        if (v == null) return false;
        String name = m.group("name");
        String key = name;
        for (String n : NETS.keySet()) if (n.equalsIgnoreCase(name)) { key = n; break; }
        NETS.merge(key, in ? -v : v, Double::sum);
        double lossNow = -NETS.get(key);
        if (lossNow < RAKE_BASE.getOrDefault(key, 0.0)) RAKE_BASE.put(key, Math.max(0, lossNow));
        if (in && aucActive && v > aucTopAmt) { aucTopAmt = v; aucTopName = name; }
        if (in) IN_TOTAL += v; else OUT_TOTAL += v;
        if (balance >= 0) balance += in ? v : -v;   // keep the balance box in step with every payment
        HISTORY.add(IN_TOTAL - OUT_TOTAL - PROFIT_BASE);
        while (HISTORY.size() > 150) HISTORY.remove(0);
        RECENT.add(0, new Bet(name, in ? v : -v, System.currentTimeMillis()));
        while (RECENT.size() > 12) RECENT.remove(RECENT.size() - 1);
        save();
        return true;
    }

    // Lines with a $ that we did NOT recognise are saved here, so the wording can be checked.
    static int pinned = -1;   // -1 = pages rotate by themselves
    static boolean balHidden = false, profitHidden = false, rakeHidden = false, aucHidden = false;

    static String lastLogged = "";

    static void logLine(String text) {
        if (text.equals(lastLogged)) return;
        lastLogged = text;
        try {
            if (Files.exists(LOG) && Files.size(LOG) > 100_000) return;
            Files.writeString(LOG, text + "\n", java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    // ---------- rakeback reset ----------
    static void rakeCmd(String[] a) {
        if (a.length < 2) return;
        String k = a[1].toLowerCase();
        boolean all = k.equals("clear") || (k.equals("reset") && a.length < 3);
        if (all) {
            for (Map.Entry<String, Double> e : NETS.entrySet())
                if (e.getValue() < 0) RAKE_BASE.put(e.getKey(), -e.getValue());
            return;
        }
        if ((k.equals("remove") || k.equals("reset")) && a.length > 2) {
            for (Map.Entry<String, Double> e : NETS.entrySet())
                if (e.getKey().equalsIgnoreCase(a[2]) && e.getValue() < 0) RAKE_BASE.put(e.getKey(), -e.getValue());
        }
    }

    // ---------- auctions ----------
    static void auctionCmd(String[] a) {
        if (a.length < 2) return;
        switch (a[1].toLowerCase()) {
            case "start" -> {
                int i = 2;
                long secs = aucDefault / 1000;
                if (a.length > 2 && a[2].matches("\\d+")) { secs = Long.parseLong(a[2]); i = 3; }
                StringBuilder sb = new StringBuilder();
                for (; i < a.length; i++) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(a[i]);
                }
                aucItem = sb.length() == 0 ? "Auction" : sb.toString();
                aucDur = Math.max(1, secs) * 1000;
                aucEnd = System.currentTimeMillis() + aucDur;
                aucTopName = null;
                aucTopAmt = 0;
                aucActive = true;
            }
            case "end" -> { if (aucActive) endAuction(); }
            case "time" -> { if (a.length > 2) aucDefault = Math.max(1, Long.parseLong(a[2])) * 1000; }
            case "clear" -> { aucActive = false; AUC_LOG.clear(); }
            default -> { }
        }
    }

    static void endAuction() {
        aucActive = false;
        AUC_LOG.add(0, new Auc(aucItem, aucTopName == null ? "-" : aucTopName, aucTopAmt, System.currentTimeMillis()));
        while (AUC_LOG.size() > 20) AUC_LOG.remove(AUC_LOG.size() - 1);
        save();
    }

    // ---------- commands ----------
    static void move(int[] p, int[] d, String[] a) {
        if (a.length < 2) return;
        if (a[1].equalsIgnoreCase("reset")) { p[0] = d[0]; p[1] = d[1]; return; }
        if (p[0] < 0) p[0] = Math.max(0, sw() - 228);
        int amt = a.length > 2 ? Integer.parseInt(a[2]) : 10;
        switch (a[1].toLowerCase()) {
            case "left" -> p[0] -= amt;
            case "right" -> p[0] += amt;
            case "up" -> p[1] -= amt;
            case "down" -> p[1] += amt;
            default -> { p[0] = Integer.parseInt(a[1]); p[1] = Integer.parseInt(a[2]); }
        }
        p[0] = Math.max(0, p[0]);
        p[1] = Math.max(0, p[1]);
    }

    static void edit(Map<String, Double> m, String[] a) {
        if (a.length < 2) return;
        String k = a[1].toLowerCase();
        if (k.equals("clear")) { m.clear(); RECENT.clear(); RAKE_BASE.clear(); return; }   // players only: In, Out and profit are NOT touched
        if (k.equals("remove") && a.length > 2) {
            String who = a[2];
            m.keySet().removeIf(n -> n.equalsIgnoreCase(who));
            RAKE_BASE.keySet().removeIf(n -> n.equalsIgnoreCase(who));
            return;
        }
        boolean set = k.equals("set");
        if (set && a.length < 4) return;
        String name = set ? a[2] : a[1];
        Double v = amount(set ? a[3] : (a.length > 2 ? a[2] : null));
        if (v == null) return;
        String key = name;
        for (String n : m.keySet()) if (n.equalsIgnoreCase(name)) { key = n; break; }
        if (set) m.put(key, v); else m.merge(key, v, Double::sum);
    }

    // accepts 412m, -3.99b, +200m, $1,000, 5k, 2t
    static Double amount(String s) {
        if (s == null) return null;
        try {
            s = s.replace(",", "").replace("$", "").trim();
            double mult = 1;
            switch (Character.toLowerCase(s.charAt(s.length() - 1))) {
                case 'k' -> mult = 1e3;
                case 'm' -> mult = 1e6;
                case 'b' -> mult = 1e9;
                case 't' -> mult = 1e12;
                default -> { }
            }
            if (mult != 1) s = s.substring(0, s.length() - 1);
            return Double.parseDouble(s) * mult;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- saving ----------
    static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("balancehud.txt");
    static final Path LOG = FabricLoader.getInstance().getConfigDir().resolve("balancehud-log.txt");

    static void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("scale,").append(SCALE).append('\n');
        sb.append("pos,bal,").append(BAL[0]).append(',').append(BAL[1]).append('\n');
        sb.append("pos,profit,").append(PROFIT_POS[0]).append(',').append(PROFIT_POS[1]).append('\n');
        sb.append("tot,").append(IN_TOTAL).append(',').append(OUT_TOTAL).append('\n');
        sb.append("base,").append(PROFIT_BASE).append('\n');
        for (double h : HISTORY) sb.append("hist,").append(h).append('\n');
        RAKE_BASE.forEach((n, v) -> sb.append("rbase,").append(n).append(',').append(v).append('\n'));
        sb.append("pos,auc,").append(AUC_POS[0]).append(',').append(AUC_POS[1]).append('\n');
        for (Auc au : AUC_LOG) sb.append("auc,").append(au.item().replace(',', ';')).append(',').append(au.winner())
                .append(',').append(au.amount()).append(',').append(au.ts()).append('\n');
        sb.append("pos,rake,").append(RAKE_POS[0]).append(',').append(RAKE_POS[1]).append('\n');
        for (Bet b : RECENT) sb.append("rec,").append(b.name()).append(',').append(b.amount()).append(',').append(b.ts()).append('\n');
        NETS.forEach((n, v) -> sb.append("net,").append(n).append(',').append(v).append('\n'));
        try { Files.writeString(CFG, sb.toString()); } catch (Exception ignored) { }
    }

    static void load() {
        try {
            for (String line : Files.readAllLines(CFG)) {
                try {
                    String[] p = line.split(",");
                    if (p.length == 2 && p[0].matches("-?\\d+")) {   // old format: just "x,y" for the balance box
                        BAL[0] = Integer.parseInt(p[0]);
                        BAL[1] = Integer.parseInt(p[1].trim());
                        continue;
                    }
                    switch (p[0]) {
                        case "pos" -> {
                            int[] t = p[1].equals("bal") ? BAL : p[1].equals("profit") ? PROFIT_POS : p[1].equals("rake") ? RAKE_POS : p[1].equals("auc") ? AUC_POS : null;
                            if (t != null) {
                                t[0] = Integer.parseInt(p[2]);
                                t[1] = Integer.parseInt(p[3]);
                            }
                        }
                        case "auc" -> AUC_LOG.add(new Auc(p[1], p[2], Double.parseDouble(p[3]), Long.parseLong(p[4])));
                        case "scale" -> SCALE = Math.max(0.4f, Math.min(1.5f, Float.parseFloat(p[1])));
                        case "net" -> NETS.put(p[1], Double.parseDouble(p[2]));
                        case "tot" -> { IN_TOTAL = Double.parseDouble(p[1]); OUT_TOTAL = Double.parseDouble(p[2]); }
                        case "rbase" -> RAKE_BASE.put(p[1], Double.parseDouble(p[2]));
                        case "base" -> PROFIT_BASE = Double.parseDouble(p[1]);
                        case "hist" -> HISTORY.add(Double.parseDouble(p[1]));
                        case "rec" -> RECENT.add(new Bet(p[1], Double.parseDouble(p[2]), p.length > 3 ? Long.parseLong(p[3]) : System.currentTimeMillis()));
                        default -> { }
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    // ---------- formatting ----------
    static Double parse(String s) {
        Matcher m = MONEY.matcher(s);
        if (!m.find()) return null;
        double v = Double.parseDouble(m.group(1).replace(",", ""));
        String suf = m.group(2);
        if (suf != null) {
            switch (Character.toLowerCase(suf.charAt(0))) {
                case 'k' -> v *= 1e3;
                case 'm' -> v *= 1e6;
                case 'b' -> v *= 1e9;
                case 't' -> v *= 1e12;
            }
        }
        return v;
    }

    static String trim(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static String money(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return "$" + trim(a / 1e12) + "T";
        if (a >= 1e9) return "$" + trim(a / 1e9) + "B";
        if (a >= 1e6) return "$" + trim(a / 1e6) + "M";
        if (a >= 1e3) return "$" + trim(a / 1e3) + "K";
        return "$" + trim(a);
    }

    // two decimals, like the example: $425.00M, $9.39B
    static String money2(double v) {
        double a = Math.abs(v);
        if (a >= 1e12) return String.format(Locale.US, "$%.2fT", a / 1e12);
        if (a >= 1e9) return String.format(Locale.US, "$%.2fB", a / 1e9);
        if (a >= 1e6) return String.format(Locale.US, "$%.2fM", a / 1e6);
        if (a >= 1e3) return String.format(Locale.US, "$%.2fK", a / 1e3);
        return "$" + trim(a);
    }

    static String signed(double v) {
        return (v >= 0 ? "+" : "-") + money2(v);
    }

    // ---------- drawing ----------
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int bg = 0xE00B0E16, bd = 0x30FFFFFF;
        g.fill(x + 2, y, x + w - 2, y + 1, bg);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, bg);
        g.fill(x, y + 2, x + w, y + h - 2, bg);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, bg);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bg);
        g.fill(x + 2, y, x + w - 2, y + 1, bd);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bd);
        g.fill(x, y + 2, x + 1, y + h - 2, bd);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, bd);
        g.fill(x + 1, y + 1, x + 2, y + 2, bd);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, bd);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, bd);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, bd);
    }

    // inner box with a thin tinted border (Top Winners / Top Losers)
    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int tint) {
        g.fill(x, y, x + w, y + h, 0x30000000);
        int b = 0x44000000 | (tint & 0xFFFFFF);
        g.fill(x, y, x + w, y + 1, b);
        g.fill(x, y + h - 1, x + w, y + h, b);
        g.fill(x, y + 1, x + 1, y + h - 1, b);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, b);
    }

    static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    // letter-spaced heading
    static int spaced(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        int cx = x;
        for (char c : s.toCharArray()) {
            String t = String.valueOf(c);
            g.text(f, t, cx, y, color, false);
            cx += f.width(t) + 1;
        }
        return cx - x;
    }

    // small drawn arrows (work with any font): left = money came in, right = money went out
    static void arrow(GuiGraphicsExtractor g, int x, int y, boolean left, int c) {
        g.fill(x, y + 3, x + 7, y + 4, c);
        if (left) {
            g.fill(x + 1, y + 2, x + 2, y + 3, c);
            g.fill(x + 1, y + 4, x + 2, y + 5, c);
            g.fill(x + 2, y + 1, x + 3, y + 2, c);
            g.fill(x + 2, y + 5, x + 3, y + 6, c);
        } else {
            g.fill(x + 5, y + 2, x + 6, y + 3, c);
            g.fill(x + 5, y + 4, x + 6, y + 5, c);
            g.fill(x + 4, y + 1, x + 5, y + 2, c);
            g.fill(x + 4, y + 5, x + 5, y + 6, c);
        }
    }

    static void small(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.7f, 0.7f);
        g.text(f, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    static String fit(Font f, String s, int maxW) {
        if (f.width(s) <= maxW) return s;
        while (s.length() > 1 && f.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        return (h / 24) + "d";
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font f = mc.font;
        g.pose().pushMatrix();
        g.pose().scale(SCALE, SCALE);
        if (!balHidden) renderBalance(g, f);
        if (!profitHidden) renderProfit(g, f);
        if (!rakeHidden) renderRake(g, f);
        if (!aucHidden) renderAuction(g, f);
        g.pose().popMatrix();
    }

    // ---- balance box ----
    static void renderBalance(GuiGraphicsExtractor g, Font f) {
        int X = BAL[0], Y = BAL[1];
        String amount = balance < 0 ? "$---" : String.format("$%,d", (long) balance);
        int w = Math.max(120, (int) (f.width(amount) * 1.8f) + 22);
        panel(g, X, Y, w, 48);
        spaced(g, f, NAME.toUpperCase(), X + 10, Y + 7, TITLE);
        g.pose().pushMatrix();
        g.pose().translate(X + 10, Y + 18);
        g.pose().scale(1.8f, 1.8f);
        g.text(f, amount, 0, 0, GREEN, false);
        g.pose().popMatrix();
        spaced(g, f, "BALANCE", X + 10, Y + 37, MUTED);
    }

    // ---- chart ----
    static int drawChart(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int n = HISTORY.size();
        double min = 0, max = 0;
        for (double v : HISTORY) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max - min < 1) max = min + 1;
        int yz = y + (int) (max / (max - min) * (h - 1));
        for (int dx = 0; dx < w; dx += 4) g.fill(x + dx, yz, x + Math.min(w, dx + 2), yz + 1, 0x44FFFFFF);
        if (n < 2) return yz;
        int last = yz;
        for (int i = 0; i < w; i++) {
            double t = (double) i * (n - 1) / (w - 1);
            int i0 = (int) Math.floor(t);
            int i1 = Math.min(n - 1, i0 + 1);
            double v = HISTORY.get(i0) + (HISTORY.get(i1) - HISTORY.get(i0)) * (t - i0);
            int yy = y + (int) ((max - v) / (max - min) * (h - 1));
            int top = Math.min(yy, yz), bot = Math.max(yy, yz) + 1;
            boolean up = v >= 0;
            g.fill(x + i, top, x + i + 1, bot, up ? 0x303DF08A : 0x30FF4D6D);
            g.fill(x + i, yy, x + i + 1, yy + 1, up ? GREEN : RED);
            last = yy;
        }
        return last;
    }

    // ---- right-hand panel (rotates: Profit, Recent Bets, Player Nets) ----
    static final int PAGES = 3;
    static final long PAGE_MS = 8000;

    static void renderProfit(GuiGraphicsExtractor g, Font f) {
        if (HISTORY.isEmpty() && NETS.isEmpty()) return;
        int w = 220, h = 232;
        int x = PROFIT_POS[0] < 0 ? sw() - w - 8 : PROFIT_POS[0];
        int y = PROFIT_POS[1];
        panel(g, x, y, w, h);

        if (balance >= 0) {
            String bt = "Bal " + money2(balance);
            small(g, f, bt, x + w - 8 - (int) (f.width(bt) * 0.7f), y + 8, MUTED);
        }

        long now = System.currentTimeMillis();
        int page = pinned >= 0 ? pinned : (int) ((now / PAGE_MS) % PAGES);
        double prog = pinned >= 0 ? 1.0 : (now % PAGE_MS) / (double) PAGE_MS;
        if (page == 0) profitPage(g, f, x, y, w);
        else if (page == 1) betsPage(g, f, x, y, w);
        else netsPage(g, f, x, y, w);

        int py = y + h - 11;
        for (int i = 0; i < PAGES; i++)
            g.fill(x + 8 + i * 8, py, x + 12 + i * 8, py + 4, i == page ? PINK : 0x55FFFFFF);
        int bx = x + 8 + PAGES * 8 + 6, ex = x + w - 8;
        g.fill(bx, py + 1, ex, py + 3, 0x18FFFFFF);
        g.fill(bx, py + 1, bx + (int) ((ex - bx) * prog), py + 3, PINK);
    }

    static void profitPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        double profit = IN_TOTAL - OUT_TOTAL - PROFIT_BASE;
        int pc = profit >= 0 ? GREEN : RED;
        spaced(g, f, "PROFIT", x + 8, y + 7, TITLE);

        g.pose().pushMatrix();
        g.pose().translate(x + 8, y + 18);
        g.pose().scale(2f, 2f);
        g.text(f, signed(profit), 0, 0, pc, false);
        g.pose().popMatrix();

        int ry = y + 39;
        g.text(f, "In", x + 8, ry, MUTED, false);
        g.text(f, money2(IN_TOTAL), x + 8 + f.width("In "), ry, WHITE, false);
        int ox = x + 100;
        g.text(f, "Out", ox, ry, MUTED, false);
        g.text(f, money2(OUT_TOTAL), ox + f.width("Out "), ry, WHITE, false);

        // chart box
        int cx = x + 8, cy = y + 52, cw = w - 16, ch = 42;
        g.fill(cx, cy, cx + cw, cy + ch, 0x30000000);
        int lastY = drawChart(g, cx + 1, cy + 1, cw - 2, ch - 2);
        double mn = 0, mx = 0;
        for (double v : HISTORY) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        small(g, f, mx > 0 ? signed(mx) : "$0", cx + 3, cy + 2, MUTED);
        small(g, f, mn < 0 ? signed(mn) : "$0", cx + 1, cy + ch + 2, MUTED);
        g.fill(x + w - 38, cy + ch + 4, x + w - 35, cy + ch + 7, PINK);
        small(g, f, "LIVE", x + w - 33, cy + ch + 2, PINK);

        // end dot + tag
        String pill = signed(profit);
        int pw = (int) (f.width(pill) * 0.7f) + 8;
        int ex2 = cx + cw - 2;
        g.fill(ex2 - 2, lastY - 1, ex2, lastY + 1, pc);
        int px = ex2 - pw - 6;
        int pyy = Math.max(cy + 1, Math.min(cy + ch - 10, lastY - 4));
        g.fill(px, pyy, px + pw, pyy + 9, 0xFF12141D);
        box(g, px, pyy, pw, 9, pc);
        small(g, f, pill, px + 4, pyy + 2, pc);

        // top winners / top losers cards
        List<Map.Entry<String, Double>> ups = new ArrayList<>();
        List<Map.Entry<String, Double>> downs = new ArrayList<>();
        for (Map.Entry<String, Double> e : NETS.entrySet()) {
            if (e.getValue() > 0) ups.add(e);
            else if (e.getValue() < 0) downs.add(e);
        }
        ups.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        downs.sort(Map.Entry.<String, Double>comparingByValue());

        int gap = 6, colW = (w - 16 - gap) / 2;
        int ty = y + 108, th = 68;
        int lx = x + 8, rx = x + 8 + colW + gap;
        card(g, lx, ty, colW, th, GREEN);
        card(g, rx, ty, colW, th, RED);
        spaced(g, f, "TOP WINNERS", lx + 5, ty + 5, GREEN);
        spaced(g, f, "TOP LOSERS", rx + 5, ty + 5, RED);
        for (int i = 0; i < 5; i++) {
            int r = ty + 17 + i * 10;
            if (i < ups.size()) {
                String amt = money2(ups.get(i).getValue());
                g.text(f, fit(f, ups.get(i).getKey(), colW - 14 - f.width(amt)), lx + 5, r, WHITE, false);
                g.text(f, amt, lx + colW - 5 - f.width(amt), r, GREEN, false);
            }
            if (i < downs.size()) {
                String amt = money2(downs.get(i).getValue());
                g.text(f, fit(f, downs.get(i).getKey(), colW - 14 - f.width(amt)), rx + 5, r, WHITE, false);
                g.text(f, amt, rx + colW - 5 - f.width(amt), r, RED, false);
            }
        }

        // last 3 payments
        for (int i = 0; i < 3 && i < RECENT.size(); i++) {
            Bet b = RECENT.get(i);
            boolean got = b.amount() >= 0;
            int r = y + 186 + i * 12;
            String amt = signed(b.amount());
            arrow(g, x + 9, r + 1, got, 0xFFBFC4D0);
            g.text(f, fit(f, b.name(), w - 62 - f.width(amt)), x + 22, r, WHITE, false);
            g.text(f, amt, x + w - 8 - f.width(amt), r, got ? GREEN : RED, false);
        }
    }

    static void betsPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        spaced(g, f, "RECENT BETS", x + 8, y + 7, TITLE);
        int ry = y + 24;
        for (int i = 0; i < RECENT.size() && i < 12; i++) {
            Bet b = RECENT.get(i);
            boolean lost = b.amount() >= 0;      // they paid you = they lost
            int col = lost ? RED : GREEN;
            if (i % 2 == 0) g.fill(x + 5, ry - 2, x + w - 5, ry + 11, 0x14FFFFFF);
            g.fill(x + 9, ry, x + 33, ry + 9, lost ? 0x44FF4D6D : 0x443DF08A);
            small(g, f, lost ? "LOST" : "WON", x + (lost ? 14 : 15), ry + 2, col);
            String amt = money2(b.amount());
            String t = ago(b.ts());
            int tw = (int) (f.width(t) * 0.7f);
            small(g, f, t, x + w - 9 - tw, ry + 2, MUTED);
            int ax = x + w - 9 - Math.max(tw, 14) - 6 - f.width(amt);
            g.text(f, amt, ax, ry, col, false);
            g.text(f, fit(f, b.name(), ax - (x + 38) - 4), x + 38, ry, WHITE, false);
            ry += 14;
        }
    }

    static void netsPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        spaced(g, f, "PLAYER NETS", x + 8, y + 7, TITLE);
        small(g, f, "+ means the player is up, - means they're down", x + 8, y + 19, MUTED);
        List<Map.Entry<String, Double>> rows = new ArrayList<>(NETS.entrySet());
        rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (rows.size() > 13) rows = rows.subList(0, 13);
        int ry = y + 32;
        for (Map.Entry<String, Double> e : rows) {
            String t = signed(e.getValue());
            g.text(f, fit(f, e.getKey(), w - 24 - f.width(t)), x + 8, ry, WHITE, false);
            g.text(f, t, x + w - 8 - f.width(t), ry, e.getValue() >= 0 ? GREEN : RED, false);
            ry += 12;
        }
    }

    // ---- auction log ----
    static void renderAuction(GuiGraphicsExtractor g, Font f) {
        long now = System.currentTimeMillis();
        if (aucActive && now >= aucEnd) endAuction();
        if (!aucActive && AUC_LOG.isEmpty()) return;
        int w = 190;
        int x = AUC_POS[0], y = AUC_POS[1];
        int rows = Math.min(5, AUC_LOG.size());
        int h = 22 + (aucActive ? 46 : 0) + (rows > 0 ? 12 + rows * 20 : 0) + 6;
        panel(g, x, y, w, h);
        spaced(g, f, "AUCTIONS", x + 8, y + 7, TITLE);
        int ry = y + 22;

        if (aucActive) {
            long left = Math.max(0, aucEnd - now);
            String t = String.format("%d:%02d", left / 60000, (left / 1000) % 60);
            g.text(f, fit(f, aucItem, w - 70), x + 8, ry + 1, WHITE, false);
            g.pose().pushMatrix();
            g.pose().translate(x + w - 8 - (int) (f.width(t) * 1.5f), ry - 1);
            g.pose().scale(1.5f, 1.5f);
            g.text(f, t, 0, 0, PINK, false);
            g.pose().popMatrix();
            int by = ry + 14;
            g.fill(x + 8, by, x + w - 8, by + 2, 0x28FFFFFF);
            g.fill(x + 8, by, x + 8 + (int) ((w - 16) * (left / (double) aucDur)), by + 2, PINK);
            small(g, f, "TOP BID", x + 8, ry + 20, MUTED);
            if (aucTopName == null) {
                g.text(f, "No bids yet", x + 8, ry + 29, MUTED, false);
            } else {
                String amt = money2(aucTopAmt);
                g.text(f, amt, x + w - 8 - f.width(amt), ry + 29, GREEN, false);
                g.text(f, fit(f, aucTopName, w - 24 - f.width(amt)), x + 8, ry + 29, WHITE, false);
            }
            ry += 46;
        }

        if (rows > 0) {
            small(g, f, "PAST AUCTIONS", x + 8, ry, MUTED);
            ry += 11;
            for (int i = 0; i < rows; i++) {
                Auc au = AUC_LOG.get(i);
                boolean sold = au.amount() > 0;
                String amt = sold ? money2(au.amount()) : "no bids";
                g.text(f, fit(f, au.item(), w - 24 - f.width(amt)), x + 8, ry, WHITE, false);
                g.text(f, amt, x + w - 8 - f.width(amt), ry, sold ? GREEN : MUTED, false);
                String t = ago(au.ts());
                small(g, f, sold ? "won by " + au.winner() : "ended", x + 8, ry + 10, MUTED);
                small(g, f, t, x + w - 8 - (int) (f.width(t) * 0.7f), ry + 10, MUTED);
                ry += 20;
            }
        }
    }

    // ---- rakeback ----
    static void renderRake(GuiGraphicsExtractor g, Font f) {
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : NETS.entrySet())
            if (e.getValue() < 0 && -e.getValue() - RAKE_BASE.getOrDefault(e.getKey(), 0.0) > 0)
                rows.add(Map.entry(e.getKey(), -e.getValue() - RAKE_BASE.getOrDefault(e.getKey(), 0.0)));
        if (rows.isEmpty()) return;
        rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (rows.size() > 7) rows = rows.subList(0, 7);
        int x = RAKE_POS[0], y = RAKE_POS[1];
        int w = 180;
        int h = 24 + rows.size() * 17 + 2;
        panel(g, x, y, w, h);
        spaced(g, f, "RAKEBACK", x + 8, y + 7, TITLE);
        small(g, f, RAKE_RULE, x + w - 8 - (int) (f.width(RAKE_RULE) * 0.7f), y + 8, MUTED);
        int ry = y + 22;
        for (Map.Entry<String, Double> e : rows) {
            double loss = e.getValue();
            boolean owed = loss >= RAKE_TARGET;
            String t = owed ? "owed " + money(loss * RAKE_PCT) : money(loss) + " / " + RAKE_LABEL;
            g.text(f, fit(f, e.getKey(), w - 24 - f.width(t)), x + 8, ry, WHITE, false);
            g.text(f, t, x + w - 8 - f.width(t), ry, owed ? GREEN : MUTED, false);
            int by = ry + 10;
            g.fill(x + 8, by, x + w - 8, by + 2, 0x28FFFFFF);
            int bw = (int) ((w - 16) * Math.min(1.0, loss / RAKE_TARGET));
            if (bw > 0) g.fill(x + 8, by, x + 8 + bw, by + 2, PINK);
            ry += 17;
        }
    }
}
