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
    static double IN_TOTAL = 0, OUT_TOTAL = 0;                       // money paid to you / paid out by you
    static final List<Double> HISTORY = new ArrayList<>();           // running profit after each payment
    static final List<Map.Entry<String, Double>> RECENT = new ArrayList<>();   // newest first; + = they paid you, - = you paid them

    // DonutSMP payment messages (a few wordings are accepted)
    static final String NAME_RE = "(?<name>[.\\w]{1,17})";
    static final String AMT_RE = "\\$(?<num>[\\d,]+(?:\\.\\d+)?)\\s*(?<suf>[kKmMbBtT])?";
    static final String PRE = "^(?:\\[[^\\]]*\\]\\s*|[^\\p{L}\\p{N}.])*";
    static final Pattern[] IN = {
        Pattern.compile(PRE + NAME_RE + " paid you " + AMT_RE),
        Pattern.compile(PRE + NAME_RE + " sent you " + AMT_RE),
        Pattern.compile(PRE + "You received " + AMT_RE + " from " + NAME_RE)
    };
    static final Pattern[] OUT = {
        Pattern.compile(PRE + "You paid " + NAME_RE + " " + AMT_RE),
        Pattern.compile(PRE + "You sent " + AMT_RE + " to " + NAME_RE)
    };

    static final Pattern MONEY = Pattern.compile("\\$\\s?([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT])?");
    static double balance = -1, start = -1;
    static boolean awaiting = false;
    static long requestedAt = 0;

    static final int TITLE = 0xFFE3B5F2, WHITE = 0xFFF2F3F7, MUTED = 0xFF9AA0AD, GREEN = 0xFF3DF08A, RED = 0xFFFF4D6D, PINK = 0xFFFF3D8B;

    @Override
    public void onInitializeClient() {
        load();

        // Commands typed by you (never sent to the server):
        //  /balhud, /profithud, /rakehud   <x> <y> | left|right|up|down [amt] | reset
        //  /nets <name> <amount> | set <name> <amount> | remove <name> | clear
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            String[] a = cmd.trim().split("\\s+");
            try {
                switch (a[0].toLowerCase()) {
                    case "balhud" -> move(BAL, BAL_D, a);
                    case "profithud" -> move(PROFIT_POS, PROFIT_D, a);
                    case "profitpage" -> pinned = a.length > 1 && a[1].matches("[1-3]") ? Integer.parseInt(a[1]) - 1 : -1;
                    case "rakehud" -> move(RAKE_POS, RAKE_D, a);
                    case "nets" -> edit(NETS, a);
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
        if (in) IN_TOTAL += v; else OUT_TOTAL += v;
        HISTORY.add(IN_TOTAL - OUT_TOTAL);
        while (HISTORY.size() > 150) HISTORY.remove(0);
        RECENT.add(0, Map.entry(name, in ? v : -v));
        while (RECENT.size() > 12) RECENT.remove(RECENT.size() - 1);
        save();
        return true;
    }

    // Lines with a $ that we did NOT recognise are saved here, so the wording can be checked.
    static int pinned = -1;   // -1 = pages rotate by themselves

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

    // ---------- commands ----------
    static void move(int[] p, int[] d, String[] a) {
        if (a.length < 2) return;
        if (a[1].equalsIgnoreCase("reset")) { p[0] = d[0]; p[1] = d[1]; return; }
        if (p[0] < 0) p[0] = Math.max(0, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 228);
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
        if (k.equals("clear")) { m.clear(); RECENT.clear(); IN_TOTAL = 0; OUT_TOTAL = 0; HISTORY.clear(); return; }
        if (k.equals("remove") && a.length > 2) {
            String who = a[2];
            m.keySet().removeIf(n -> n.equalsIgnoreCase(who));
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
        sb.append("pos,bal,").append(BAL[0]).append(',').append(BAL[1]).append('\n');
        sb.append("pos,profit,").append(PROFIT_POS[0]).append(',').append(PROFIT_POS[1]).append('\n');
        sb.append("tot,").append(IN_TOTAL).append(',').append(OUT_TOTAL).append('\n');
        for (double h : HISTORY) sb.append("hist,").append(h).append('\n');
        sb.append("pos,rake,").append(RAKE_POS[0]).append(',').append(RAKE_POS[1]).append('\n');
        for (Map.Entry<String, Double> e : RECENT) sb.append("rec,").append(e.getKey()).append(',').append(e.getValue()).append('\n');
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
                            int[] t = p[1].equals("bal") ? BAL : p[1].equals("profit") ? PROFIT_POS : p[1].equals("rake") ? RAKE_POS : null;
                            if (t != null) {
                                t[0] = Integer.parseInt(p[2]);
                                t[1] = Integer.parseInt(p[3]);
                            }
                        }
                        case "net" -> NETS.put(p[1], Double.parseDouble(p[2]));
                        case "tot" -> { IN_TOTAL = Double.parseDouble(p[1]); OUT_TOTAL = Double.parseDouble(p[2]); }
                        case "hist" -> HISTORY.add(Double.parseDouble(p[1]));
                        case "rec" -> RECENT.add(Map.entry(p[1], Double.parseDouble(p[2])));
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
        g.fill(x, y, x + w, y + h, 0xC80E1016);
        g.outline(x, y, w, h, 0x24FFFFFF);
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font f = mc.font;
        renderBalance(g, f);
        renderProfit(g, f);
        renderRake(g, f);
    }

    static void renderBalance(GuiGraphicsExtractor g, Font f) {
        int X = BAL[0], Y = BAL[1];
        String amount = balance < 0 ? "$---" : String.format("$%,d", (long) balance);
        int w = Math.max(f.width(amount) * 2, f.width(NAME.toUpperCase())) + 24;
        w = Math.max(w, 150);
        int h = 52;

        panel(g, X, Y, w, h);
        g.text(f, NAME.toUpperCase(), X + 10, Y + 8, WHITE, true);

        g.pose().pushMatrix();
        g.pose().translate(X + 10, Y + 19);
        g.pose().scale(2f, 2f);
        g.text(f, amount, 0, 0, GREEN, true);
        g.pose().popMatrix();

        g.text(f, "BALANCE \u00B7 EST.", X + 10, Y + 40, MUTED, false);

        if (balance >= 0 && start >= 0) {
            double d = balance - start;
            String t = (d >= 0 ? "+" : "-") + money(d);
            g.text(f, t, X + w - 10 - f.width(t), Y + 40, d >= 0 ? GREEN : RED, false);
        }
    }

    static String fit(Font f, String s, int maxW) {
        if (f.width(s) <= maxW) return s;
        while (s.length() > 1 && f.width(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    static int drawChart(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int n = HISTORY.size();
        double min = 0, max = 0;
        for (double v : HISTORY) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max - min < 1) max = min + 1;
        int yz = y + (int) (max / (max - min) * (h - 1));
        g.fill(x, yz, x + w, yz + 1, 0x22FFFFFF);
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
            g.fill(x + i, top, x + i + 1, bot, up ? 0x403DF08A : 0x40FF4D6D);
            g.fill(x + i, yy, x + i + 1, yy + 1, up ? GREEN : RED);
            last = yy;
        }
        return last;
    }

    static void small(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.7f, 0.7f);
        g.text(f, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    static final int PAGES = 3;
    static final long PAGE_MS = 8000;

    static void renderProfit(GuiGraphicsExtractor g, Font f) {
        if (HISTORY.isEmpty() && NETS.isEmpty()) return;
        int w = 250, h = 236;
        int x = PROFIT_POS[0] < 0
                ? Minecraft.getInstance().getWindow().getGuiScaledWidth() - w - 8
                : PROFIT_POS[0];
        int y = PROFIT_POS[1];
        panel(g, x, y, w, h);

        long now = System.currentTimeMillis();
        int page = pinned >= 0 ? pinned : (int) ((now / PAGE_MS) % PAGES);
        double prog = pinned >= 0 ? 1.0 : (now % PAGE_MS) / (double) PAGE_MS;
        if (page == 0) profitPage(g, f, x, y, w);
        else if (page == 1) netsPage(g, f, x, y, w);
        else recentPage(g, f, x, y, w);

        // page dots + timer line
        int py = y + h - 12;
        for (int i = 0; i < PAGES; i++)
            g.fill(x + 8 + i * 8, py, x + 12 + i * 8, py + 4, i == page ? PINK : 0x55FFFFFF);
        int bx = x + 8 + PAGES * 8 + 8, ex = x + w - 8;
        g.fill(bx, py + 1, ex, py + 3, 0x22FFFFFF);
        g.fill(bx, py + 1, bx + (int) ((ex - bx) * prog), py + 3, PINK);
    }

    static void profitPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        double profit = IN_TOTAL - OUT_TOTAL;
        g.text(f, "PROFIT", x + 8, y + 7, WHITE, true);

        g.pose().pushMatrix();
        g.pose().translate(x + 8, y + 19);
        g.pose().scale(2f, 2f);
        g.text(f, signed(profit), 0, 0, profit >= 0 ? GREEN : RED, true);
        g.pose().popMatrix();

        int ry = y + 40;
        g.text(f, "In", x + 8, ry, MUTED, false);
        g.text(f, money2(IN_TOTAL), x + 8 + f.width("In "), ry, WHITE, false);
        int ox = x + 110;
        g.text(f, "Out", ox, ry, MUTED, false);
        g.text(f, money2(OUT_TOTAL), ox + f.width("Out "), ry, WHITE, false);

        // chart with min / max labels, end tag and LIVE
        int lastY = drawChart(g, x + 8, y + 54, w - 16, 44);
        double mn = 0, mx = 0;
        for (double v : HISTORY) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        small(g, f, mx > 0 ? signed(mx) : "$0", x + 10, y + 55, MUTED);
        small(g, f, mn < 0 ? signed(mn) : "$0", x + 10, y + 90, MUTED);
        String pill = signed(profit);
        int pw = (int) (f.width(pill) * 0.7f) + 8;
        int px = x + w - 8 - pw - 4;
        int pyy = Math.max(y + 54, Math.min(y + 54 + 44 - 9, lastY - 5));
        g.fill(px, pyy, px + pw, pyy + 9, profit >= 0 ? GREEN : RED);
        small(g, f, pill, px + 4, pyy + 1, profit >= 0 ? 0xFF0E1016 : WHITE);
        g.fill(x + w - 40, y + 101, x + w - 37, y + 104, PINK);
        small(g, f, "LIVE", x + w - 34, y + 99, PINK);

        List<Map.Entry<String, Double>> ups = new ArrayList<>();
        List<Map.Entry<String, Double>> downs = new ArrayList<>();
        for (Map.Entry<String, Double> e : NETS.entrySet()) {
            if (e.getValue() > 0) ups.add(e);
            else if (e.getValue() < 0) downs.add(e);
        }
        ups.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        downs.sort(Map.Entry.<String, Double>comparingByValue());

        int colW = (w - 24) / 2;
        int hy = y + 110;
        g.text(f, "TOP WINNERS", x + 8, hy, GREEN, true);
        g.text(f, "TOP LOSERS", x + 16 + colW, hy, RED, true);
        for (int i = 0; i < 5; i++) {
            int r = hy + 12 + i * 10;
            if (i < ups.size()) {
                Map.Entry<String, Double> e = ups.get(i);
                String amt = money2(e.getValue());
                g.text(f, fit(f, e.getKey(), colW - f.width(amt) - 6), x + 8, r, WHITE, false);
                g.text(f, amt, x + 8 + colW - f.width(amt), r, GREEN, false);
            }
            if (i < downs.size()) {
                Map.Entry<String, Double> e = downs.get(i);
                String amt = money2(e.getValue());
                g.text(f, fit(f, e.getKey(), colW - f.width(amt) - 6), x + 16 + colW, r, WHITE, false);
                g.text(f, amt, x + 16 + colW + colW - f.width(amt), r, RED, false);
            }
        }

        // recent payments feed (last 3)
        g.fill(x + 8, y + 176, x + w - 8, y + 177, 0x22FFFFFF);
        for (int i = 0; i < 3 && i < RECENT.size(); i++) {
            Map.Entry<String, Double> e = RECENT.get(i);
            boolean got = e.getValue() >= 0;
            int r = y + 182 + i * 14;
            String amt = signed(e.getValue());
            g.text(f, got ? "<-" : "->", x + 8, r, got ? GREEN : RED, false);
            g.text(f, fit(f, e.getKey(), w - 70 - f.width(amt)), x + 28, r, WHITE, false);
            g.text(f, amt, x + w - 8 - f.width(amt), r, got ? GREEN : RED, false);
        }
    }

    static void netsPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        g.text(f, "PLAYER NETS", x + 8, y + 7, TITLE, true);
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

    static void recentPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        g.text(f, "RECENT PAYMENTS", x + 8, y + 7, TITLE, true);
        int ry = y + 24;
        for (Map.Entry<String, Double> e : RECENT) {
            boolean got = e.getValue() >= 0;
            String amt = signed(e.getValue());
            g.text(f, got ? "<-" : "->", x + 8, ry, got ? GREEN : RED, false);
            g.text(f, fit(f, e.getKey(), w - 70 - f.width(amt)), x + 28, ry, WHITE, false);
            g.text(f, amt, x + w - 8 - f.width(amt), ry, got ? GREEN : RED, false);
            ry += 14;
        }
    }

    static void renderRake(GuiGraphicsExtractor g, Font f) {
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : NETS.entrySet())
            if (e.getValue() < 0) rows.add(Map.entry(e.getKey(), -e.getValue()));
        if (rows.isEmpty()) return;
        rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (rows.size() > 8) rows = rows.subList(0, 8);
        int x = RAKE_POS[0], y = RAKE_POS[1];
        int w = Math.max(190, f.width("RAKEBACK") + f.width(RAKE_RULE) + 30);
        int h = 26 + rows.size() * 19;
        panel(g, x, y, w, h);
        g.text(f, "RAKEBACK", x + 8, y + 7, TITLE, true);
        g.text(f, RAKE_RULE, x + w - 8 - f.width(RAKE_RULE), y + 7, MUTED, false);
        int ry = y + 21;
        for (Map.Entry<String, Double> e : rows) {
            double loss = e.getValue();
            boolean owed = loss >= RAKE_TARGET;
            g.text(f, e.getKey(), x + 8, ry, WHITE, false);
            String t = owed ? "owed " + money(loss * RAKE_PCT) : money(loss) + " / " + RAKE_LABEL;
            g.text(f, t, x + w - 8 - f.width(t), ry, owed ? GREEN : MUTED, false);
            int by = ry + 10;
            g.fill(x + 8, by, x + w - 8, by + 2, 0x33FFFFFF);
            int bw = (int) ((w - 16) * Math.min(1.0, loss / RAKE_TARGET));
            if (bw > 0) g.fill(x + 8, by, x + 8 + bw, by + 2, PINK);
            ry += 19;
        }
    }
}
