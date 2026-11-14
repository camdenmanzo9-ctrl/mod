package com.snowy.balancehud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
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
    static int BAL_W = 0;   // extra width for the balance box (/balwidth)
    static float BAL_SCALE = 1f, PROFIT_SCALE = 1f, RAKE_SCALE = 1f;   // per-panel size on top of /hudscale
    static float SCALE = 0.65f;   // overall size of all panels (change in game with /hudscale 0.5 ... 1.5)

    // Poppins font (files live in assets/balancehud/font/)
    static final FontDescription FD_MED = new FontDescription.Resource(Identifier.fromNamespaceAndPath("balancehud", "hud"));
    static final FontDescription FD_BOLD = FD_MED;   // one font file only (Poppins Medium) to keep the setup short

    static final boolean USE_FONT = true;   // false = use Minecraft's own font (no font files needed)

    static Component fnt(String s) {
        return USE_FONT ? Component.literal(s).withStyle(Style.EMPTY.withFont(FD_MED)) : Component.literal(s);
    }

    static Component fntB(String s) {
        return USE_FONT ? Component.literal(s).withStyle(Style.EMPTY.withFont(FD_BOLD).withBold(true)) : Component.literal(s);
    }

    static int twb(Font f, String s) { return f.width(fntB(s)); }
    static int tw(Font f, String s) { return f.width(fnt(s)); }
    static void tx(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color, boolean shadow) {
        g.text(f, fnt(s), x, y, color, shadow);
    }
    static void txb(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.text(f, fntB(s), x, y, color, false);
    }

    static float cl(float v) { return Math.max(0.4f, Math.min(1.5f, v)); }

    // scale one panel around its own top-left corner
    static void begin(GuiGraphicsExtractor g, int x, int y, float m) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(m, m);
        g.pose().translate(-x, -y);
    }

    static void end(GuiGraphicsExtractor g) { g.pose().popMatrix(); }

    static int sw() {
        return (int) (Minecraft.getInstance().getWindow().getGuiScaledWidth() / SCALE);
    }

    static double balance = -1, start = -1;
    static int pinned = -1;   // -1 = pages rotate by themselves
    static boolean balHidden = false, profitHidden = false, rakeHidden = false;
    static double balShown = -1;   // the number drawn on screen: it counts up/down to "balance"
    static long balTick = 0;
    static boolean awaiting = false;
    static long requestedAt = 0;

    static final int TITLE = 0xFFD88A9E, WHITE = 0xFFFFFFFF, MUTED = 0xFF9EA0B4, GREEN = 0xFF55E875, RED = 0xFFF14A7B, PINK = 0xFFD88A9E;

    @Override
    public void onInitializeClient() {
        load();

        // Commands typed by you (never sent to the server):
        //  /rake remove Name | clear   (resets only rakeback)
        //  /profit [profit|bets|nets|auto|rake|balance|hide|show|reset|clear]
        //  /balwidth 60  (make the balance box longer)
        //  /profitscale 0.8  /balscale  /rakescale   (size of one panel)
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
                    case "nets" -> edit(NETS, a);
                    case "hudscale" -> { if (a.length > 1) SCALE = cl(Float.parseFloat(a[1])); }
                    case "balwidth" -> { if (a.length > 1) BAL_W = Math.max(0, Math.min(600, Integer.parseInt(a[1]))); }
                    case "balscale" -> { if (a.length > 1) BAL_SCALE = cl(Float.parseFloat(a[1])); }
                    case "profitscale" -> { if (a.length > 1) PROFIT_SCALE = cl(Float.parseFloat(a[1])); }
                    case "rakescale" -> { if (a.length > 1) RAKE_SCALE = cl(Float.parseFloat(a[1])); }
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
                                case "balance" -> balHidden = !balHidden;
                                case "hide" -> { balHidden = true; profitHidden = true; rakeHidden = true; }
                                case "show" -> { balHidden = false; profitHidden = false; rakeHidden = false; }
                                default -> { return true; }
                            }
                        }
                    }
                    default -> { return true; }
                }
                save();
                writeShared();   // keep the dashboard's Profit Panel switches in step
            } catch (Exception ignored) { }
            return false;
        });

        // When YOU run /bal (or /balance), read the reply and update the balance box.
        ClientSendMessageEvents.COMMAND.register(cmd -> {
            String c = cmd.trim().toLowerCase();
            if (c.equals("bal") || c.equals("balance")) {
                awaiting = true;
                requestedAt = System.currentTimeMillis();
            }
        });

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (overlay) return;   // ignore the action bar: only normal chat lines are read
            String text = msg.getString();
            if (trackPay(text)) return;
            if (awaiting) {
                if (System.currentTimeMillis() - requestedAt > 5000) {
                    awaiting = false;
                } else {
                    Double v = parse(text);
                    if (v != null) {
                        balance = v;
                        if (start < 0) start = v;
                        awaiting = false;
                        save();
                        return;
                    }
                }
            }
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
        if (in) IN_TOTAL += v; else OUT_TOTAL += v;
        if (balance >= 0) balance += in ? v : -v;   // keep the balance box in step with every payment
        HISTORY.add(IN_TOTAL - OUT_TOTAL - PROFIT_BASE);
        while (HISTORY.size() > 300) HISTORY.remove(0);
        RECENT.add(0, new Bet(name, in ? v : -v, System.currentTimeMillis()));
        while (RECENT.size() > 12) RECENT.remove(RECENT.size() - 1);
        save();
        return true;
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

    static void save() {
        StringBuilder sb = new StringBuilder();
        sb.append("balw,").append(BAL_W).append('\n');
        sb.append("balance,").append(balance).append('\n');
        sb.append("scale,").append(SCALE).append('\n');
        sb.append("scales,").append(BAL_SCALE).append(',').append(PROFIT_SCALE).append(',').append(RAKE_SCALE).append('\n');
        sb.append("pos,bal,").append(BAL[0]).append(',').append(BAL[1]).append('\n');
        sb.append("pos,profit,").append(PROFIT_POS[0]).append(',').append(PROFIT_POS[1]).append('\n');
        sb.append("tot,").append(IN_TOTAL).append(',').append(OUT_TOTAL).append('\n');
        sb.append("base,").append(PROFIT_BASE).append('\n');
        for (double h : HISTORY) sb.append("hist,").append(h).append('\n');
        RAKE_BASE.forEach((n, v) -> sb.append("rbase,").append(n).append(',').append(v).append('\n'));
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
                            int[] t = p[1].equals("bal") ? BAL : p[1].equals("profit") ? PROFIT_POS : p[1].equals("rake") ? RAKE_POS : null;
                            if (t != null) {
                                t[0] = Integer.parseInt(p[2]);
                                t[1] = Integer.parseInt(p[3]);
                            }
                        }
                        case "scales" -> {
                            BAL_SCALE = cl(Float.parseFloat(p[1]));
                            PROFIT_SCALE = cl(Float.parseFloat(p[2]));
                            RAKE_SCALE = cl(Float.parseFloat(p[3]));
                        }
                        case "balw" -> BAL_W = Math.max(0, Math.min(600, Integer.parseInt(p[1])));
                        case "balance" -> balance = Double.parseDouble(p[1]);
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

    // ---------- on/off switches shared with Snowy's Dashboard (PROFIT PANEL tab) ----------
    // config/snowy-panels.txt holds lines like "balance=on", "profit=off", "rakeback=on".
    static final Path SHARED = FabricLoader.getInstance().getConfigDir().resolve("snowy-panels.txt");
    static long sharedChecked = 0, sharedMtime = -1;

    static void readShared() {
        long now = System.currentTimeMillis();
        if (now - sharedChecked < 500) return;   // look at the file twice a second at most
        sharedChecked = now;
        try {
            if (!Files.exists(SHARED)) return;
            long mt = Files.getLastModifiedTime(SHARED).toMillis();
            if (mt == sharedMtime) return;
            sharedMtime = mt;
            for (String line : Files.readAllLines(SHARED)) {
                String[] p = line.split("=");
                if (p.length < 2) continue;
                boolean on = p[1].trim().equalsIgnoreCase("on");
                switch (p[0].trim().toLowerCase()) {
                    case "balance" -> balHidden = !on;
                    case "profit" -> profitHidden = !on;
                    case "rakeback" -> rakeHidden = !on;
                    default -> { }
                }
            }
        } catch (Exception ignored) { }
    }

    static void writeShared() {
        try {
            Files.writeString(SHARED, "balance=" + (balHidden ? "off" : "on") + "\n"
                    + "profit=" + (profitHidden ? "off" : "on") + "\n"
                    + "rakeback=" + (rakeHidden ? "off" : "on") + "\n");
            sharedMtime = Files.getLastModifiedTime(SHARED).toMillis();
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
        int bg = 0xEB11161F, bd = 0xFF1F2735;
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
    static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 2, y, x + w - 2, y + 1, c);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c);
        g.fill(x, y + 2, x + w, y + h - 2, c);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, c);
    }

    // inner box with a thin tinted border and softly rounded corners (Top Winners / Top Losers)
    static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int tint) {
        rrect(g, x, y, w, h, 0x30000000);
        int b = 0x44000000 | (tint & 0xFFFFFF);
        g.fill(x + 2, y, x + w - 2, y + 1, b);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, b);
        g.fill(x, y + 2, x + 1, y + h - 2, b);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, b);
        g.fill(x + 1, y + 1, x + 2, y + 2, b);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, b);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, b);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, b);
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
            String ch = String.valueOf(c);
            txb(g, f, ch, cx, y, color);
            cx += f.width(fntB(ch)) + 1;
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
        tx(g, f, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    static void smallB(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.7f, 0.7f);
        txb(g, f, s, 0, 0, color);
        g.pose().popMatrix();
    }

    static String fit(Font f, String s, int maxW) {
        if (tw(f, s) <= maxW) return s;
        while (s.length() > 1 && tw(f, s + "..") > maxW) s = s.substring(0, s.length() - 1);
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
        readShared();   // pick up on/off changes from the dashboard's PROFIT PANEL tab
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font f = mc.font;
        g.pose().pushMatrix();
        g.pose().scale(SCALE, SCALE);
        if (!balHidden) { begin(g, BAL[0], BAL[1], BAL_SCALE); renderBalance(g, f); end(g); }
        if (!profitHidden) renderProfit(g, f);
        if (!rakeHidden) { begin(g, RAKE_POS[0], RAKE_POS[1], RAKE_SCALE); renderRake(g, f); end(g); }
        g.pose().popMatrix();
    }

    // ---- balance box ----
    static void renderBalance(GuiGraphicsExtractor g, Font f) {
        int X = BAL[0], Y = BAL[1];
        long nowN = System.nanoTime();
        double dt = balTick == 0 ? 0 : Math.min(0.1, (nowN - balTick) / 1e9);
        balTick = nowN;
        if (balance < 0) balShown = -1;
        else if (balShown < 0 || Math.abs(balance - balShown) < 1) balShown = balance;
        else balShown += (balance - balShown) * (1 - Math.exp(-dt * 5));   // rolls to the new number in about half a second
        String amount = balShown < 0 ? "$---" : String.format("$%,d", Math.round(balShown));
        int w = Math.max(120, (int) (tw(f, amount) * 1.8f) + 22) + BAL_W;
        panel(g, X, Y, w, 48);
        spaced(g, f, NAME.toUpperCase(), X + 10, Y + 7, TITLE);
        g.pose().pushMatrix();
        g.pose().translate(X + 10, Y + 18);
        g.pose().scale(1.8f, 1.8f);
        txb(g, f, amount, 0, 0, GREEN);
        g.pose().popMatrix();
        spaced(g, f, "BALANCE", X + 10, Y + 37, MUTED);
    }

    // ---- chart ----
    static int drawChart(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int n = HISTORY.size();
        double min = 0, max = 0;
        for (double v : HISTORY) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max - min < 1) max = min + 1;
        int yz = y + (int) (max / (max - min) * (h - 2));
        for (int dx = 0; dx < w; dx += 4) g.fill(x + dx, yz, x + Math.min(w, dx + 2), yz + 1, 0x33FFFFFF);
        if (n < 2) return yz;
        int last = yz, prev = -1;
        for (int i = 0; i < w; i++) {
            double t = (double) i * (n - 1) / (w - 1);
            int i0 = (int) Math.floor(t);
            int i1 = Math.min(n - 1, i0 + 1);
            double v = HISTORY.get(i0) + (HISTORY.get(i1) - HISTORY.get(i0)) * (t - i0);
            int yy = y + (int) ((max - v) / (max - min) * (h - 2));
            boolean up = v >= 0;
            int rgb = (up ? GREEN : RED) & 0xFFFFFF;
            // very faint fill between the line and the zero line
            int from = up ? yy + 1 : yz + 1;
            int to = up ? yz : yy;
            int span = to - from;
            if (span > 0) {
                int bands = Math.min(5, span);
                for (int b = 0; b < bands; b++) {
                    double k = up ? (double) b / bands : (double) (bands - 1 - b) / bands;
                    int a = (int) (0x1E * (1.0 - k));
                    int y0 = from + b * span / bands, y1 = from + (b + 1) * span / bands;
                    if (y1 > y0) g.fill(x + i, y0, x + i + 1, y1, (a << 24) | rgb);
                }
            }
            // thin line, joined to the previous column so steep parts stay connected
            int lo = prev < 0 ? yy : Math.min(prev, yy), hi = prev < 0 ? yy : Math.max(prev, yy);
            g.fill(x + i, lo, x + i + 1, hi + 1, 0xA0000000 | rgb);
            prev = yy;
            last = yy;
        }
        return last;
    }

    // ---- right-hand panel (rotates: Profit, Recent Bets, Player Nets) ----
    static final int PAGES = 3;
    static final long PAGE_MS = 8000;

    static void renderProfit(GuiGraphicsExtractor g, Font f) {
        int w = 220, h = 262;
        int x = PROFIT_POS[0] < 0 ? sw() - 8 - (int) (w * PROFIT_SCALE) : PROFIT_POS[0];
        int y = PROFIT_POS[1];
        begin(g, x, y, PROFIT_SCALE);
        panel(g, x, y, w, h);

        if (balance >= 0) {
            String bt = "Bal " + money2(balance);
            small(g, f, bt, x + w - 8 - (int) (tw(f, bt) * 0.7f), y + 8, MUTED);
        }

        long now = System.currentTimeMillis();
        int page = pinned >= 0 ? pinned : (int) ((now / PAGE_MS) % PAGES);
        double prog = pinned >= 0 ? 1.0 : (now % PAGE_MS) / (double) PAGE_MS;
        if (page == 0) profitPage(g, f, x, y, w);
        else if (page == 1) betsPage(g, f, x, y, w);
        else netsPage(g, f, x, y, w);

        int py = y + h - 11;
        for (int i = 0; i < PAGES; i++) {   // round page dots
            int dx = x + 8 + i * 8, c = i == page ? PINK : 0x55FFFFFF;
            g.fill(dx + 1, py, dx + 3, py + 1, c);       // top
            g.fill(dx, py + 1, dx + 4, py + 3, c);       // middle
            g.fill(dx + 1, py + 3, dx + 3, py + 4, c);   // bottom (no overlap, so faint dots stay even)
        }
        int bx = x + 8 + PAGES * 8 + 6, ex = x + w - 8;
        g.fill(bx, py + 1, ex, py + 3, 0x18FFFFFF);
        g.fill(bx, py + 1, bx + (int) ((ex - bx) * prog), py + 3, PINK);
        end(g);
    }

    static void profitPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        double profit = IN_TOTAL - OUT_TOTAL - PROFIT_BASE;
        int pc = profit >= 0 ? GREEN : RED;
        spaced(g, f, "PROFIT", x + 8, y + 7, TITLE);

        g.pose().pushMatrix();
        g.pose().translate(x + 8, y + 18);
        g.pose().scale(2.3f, 2.3f);
        txb(g, f, signed(profit), 0, 0, pc);
        g.pose().popMatrix();

        int ry = y + 46;
        tx(g, f, "In", x + 8, ry, MUTED, false);
        txb(g, f, money2(IN_TOTAL), x + 8 + tw(f, "In "), ry, WHITE);
        int ox = x + 104;
        tx(g, f, "Out", ox, ry, MUTED, false);
        txb(g, f, money2(OUT_TOTAL), ox + tw(f, "Out "), ry, WHITE);

        // chart box with high / low labels, end tag and LIVE
        int cx = x + 8, cy = y + 61, cw = w - 16, ch = 50;
        rrect(g, cx, cy, cw, ch, 0x30000000);
        int lastY = drawChart(g, cx + 3, cy + 4, cw - 6, ch - 8);
        double mn = 0, mx = 0;
        for (double v : HISTORY) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        small(g, f, mx > 0 ? signed(mx) : "+$0", cx + 5, cy + 3, MUTED);
        small(g, f, mn < 0 ? signed(mn) : "+$0", cx + 1, cy + ch + 3, MUTED);
        g.fill(x + w - 38, cy + ch + 5, x + w - 35, cy + ch + 8, RED);
        small(g, f, "LIVE", x + w - 33, cy + ch + 3, MUTED);

        String pill = signed(profit);
        int pw = (int) (twb(f, pill) * 0.7f) + 10;
        int ex2 = cx + cw - 5;
        int lineC = 0xA0000000 | (pc & 0xFFFFFF);
        g.fill(ex2 - 2, lastY - 2, ex2 + 3, lastY + 3, 0x40000000 | (pc & 0xFFFFFF));   // soft glow
        g.fill(ex2 - 1, lastY - 1, ex2 + 2, lastY + 2, lineC);
        g.fill(ex2 - 2, lastY, ex2 + 3, lastY + 1, lineC);
        int px = ex2 - pw - 4;
        int pyy = Math.max(cy + 2, Math.min(cy + ch - 12, lastY - 13));
        rrect(g, px, pyy, pw, 10, 0x40000000 | (pc & 0xFFFFFF));
        smallB(g, f, pill, px + 5, pyy + 2, pc);

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
        int ty = y + 126, th = 76;
        int lx = x + 8, rx = x + 8 + colW + gap;
        card(g, lx, ty, colW, th, GREEN);
        card(g, rx, ty, colW, th, RED);
        spaced(g, f, "TOP WINNERS", lx + 5, ty + 5, GREEN);
        spaced(g, f, "TOP LOSERS", rx + 5, ty + 5, RED);
        if (ups.isEmpty()) tx(g, f, "None yet", lx + 5, ty + 19, MUTED, false);
        if (downs.isEmpty()) tx(g, f, "None yet", rx + 5, ty + 19, MUTED, false);
        for (int i = 0; i < 5; i++) {
            int r = ty + 19 + i * 11;
            if (i < ups.size()) {
                String amt = money2(ups.get(i).getValue());
                tx(g, f, fit(f, ups.get(i).getKey(), colW - 14 - twb(f, amt)), lx + 5, r, WHITE, false);
                txb(g, f, amt, lx + colW - 5 - twb(f, amt), r, GREEN);
            }
            if (i < downs.size()) {
                String amt = money2(downs.get(i).getValue());
                tx(g, f, fit(f, downs.get(i).getKey(), colW - 14 - twb(f, amt)), rx + 5, r, WHITE, false);
                txb(g, f, amt, rx + colW - 5 - twb(f, amt), r, RED);
            }
        }

        // last 3 payments
        if (RECENT.isEmpty()) tx(g, f, "No payments yet", x + 9, y + 209, MUTED, false);
        for (int i = 0; i < 3 && i < RECENT.size(); i++) {
            Bet b = RECENT.get(i);
            boolean got = b.amount() >= 0;
            int r = y + 209 + i * 14;
            String amt = signed(b.amount());
            arrow(g, x + 9, r + 1, got, 0xFFB4B8C8);
            txb(g, f, fit(f, b.name(), w - 62 - twb(f, amt)), x + 22, r, WHITE);
            txb(g, f, amt, x + w - 8 - twb(f, amt), r, got ? GREEN : RED);
        }
    }

    static void betsPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        spaced(g, f, "RECENT BETS", x + 8, y + 7, TITLE);
        int ry = y + 24;
        if (RECENT.isEmpty()) tx(g, f, "No payments yet", x + 8, ry, MUTED, false);
        for (int i = 0; i < RECENT.size() && i < 12; i++) {
            Bet b = RECENT.get(i);
            boolean lost = b.amount() >= 0;      // they paid you = they lost
            int col = lost ? RED : GREEN;
            if (i % 2 == 0) g.fill(x + 5, ry - 2, x + w - 5, ry + 12, 0x14FFFFFF);
            g.fill(x + 9, ry, x + 33, ry + 9, lost ? 0x44F14A7B : 0x4459F58C);
            small(g, f, lost ? "LOST" : "WON", x + (lost ? 14 : 15), ry + 2, col);
            String amt = money2(b.amount());
            String t = ago(b.ts());
            int tw = (int) (tw(f, t) * 0.7f);
            small(g, f, t, x + w - 9 - tw, ry + 2, MUTED);
            int ax = x + w - 9 - Math.max(tw, 14) - 6 - tw(f, amt);
            tx(g, f, amt, ax, ry, col, false);
            tx(g, f, fit(f, b.name(), ax - (x + 38) - 4), x + 38, ry, WHITE, false);
            ry += 16;
        }
    }

    static void netsPage(GuiGraphicsExtractor g, Font f, int x, int y, int w) {
        spaced(g, f, "PLAYER NETS", x + 8, y + 7, TITLE);
        small(g, f, "+ means the player is up, - means they're down", x + 8, y + 19, MUTED);
        List<Map.Entry<String, Double>> rows = new ArrayList<>(NETS.entrySet());
        rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (rows.size() > 13) rows = rows.subList(0, 13);
        int ry = y + 32;
        if (rows.isEmpty()) tx(g, f, "No players yet", x + 8, ry, MUTED, false);
        for (Map.Entry<String, Double> e : rows) {
            String t = signed(e.getValue());
            tx(g, f, fit(f, e.getKey(), w - 24 - tw(f, t)), x + 8, ry, WHITE, false);
            tx(g, f, t, x + w - 8 - tw(f, t), ry, e.getValue() >= 0 ? GREEN : RED, false);
            ry += 14;
        }
    }

    // ---- rakeback ----
    static void renderRake(GuiGraphicsExtractor g, Font f) {
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : NETS.entrySet())
            if (e.getValue() < 0 && -e.getValue() - RAKE_BASE.getOrDefault(e.getKey(), 0.0) > 0)
                rows.add(Map.entry(e.getKey(), -e.getValue() - RAKE_BASE.getOrDefault(e.getKey(), 0.0)));
        rows.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (rows.size() > 7) rows = rows.subList(0, 7);
        int x = RAKE_POS[0], y = RAKE_POS[1];
        int w = 180;
        int h = 24 + Math.max(1, rows.size()) * 20 + 4;
        panel(g, x, y, w, h);
        spaced(g, f, "RAKEBACK", x + 8, y + 7, TITLE);
        small(g, f, RAKE_RULE, x + w - 8 - (int) (tw(f, RAKE_RULE) * 0.7f), y + 8, MUTED);
        int ry = y + 22;
        if (rows.isEmpty()) tx(g, f, "No rakeback yet", x + 8, y + 26, MUTED, false);
        for (Map.Entry<String, Double> e : rows) {
            double loss = e.getValue();
            boolean owed = loss >= RAKE_TARGET;
            double frac = Math.min(1.0, loss / RAKE_TARGET);
            String t = owed ? "owed " + money(loss * RAKE_PCT) : money(loss) + " / " + RAKE_LABEL;
            rrect(g, x + 5, ry - 3, w - 10, 18, 0x30000000);                 // row card
            tx(g, f, fit(f, e.getKey(), w - 28 - tw(f, t)), x + 10, ry, WHITE, false);
            tx(g, f, t, x + w - 10 - tw(f, t), ry, owed ? GREEN : WHITE, false);
            int by = ry + 11;
            g.fill(x + 10, by, x + w - 10, by + 2, 0x28FFFFFF);
            int bw = (int) ((w - 20) * frac);
            if (bw > 0) g.fill(x + 10, by, x + 10 + bw, by + 2, frac >= 0.75 ? GREEN : PINK);   // green once close to the target
            ry += 20;
        }
    }
}