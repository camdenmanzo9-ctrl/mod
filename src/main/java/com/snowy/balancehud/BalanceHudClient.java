package com.snowy.balancehud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BalanceHudClient implements ClientModInitializer {
    // ===== EDIT THESE =====
    static final String NAME = "2amsnowy";
    static final int X = 8, Y = 8;               // HUD position
    // ======================

    static final Pattern MONEY = Pattern.compile("\\$\\s?([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT])?");

    static double balance = -1, start = -1;
    static boolean awaiting = false;
    static long requestedAt = 0;

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> { balance = -1; start = -1; });

        // When YOU run /bal (or /balance), read the reply and update the HUD. The reply stays visible in chat.
        ClientSendMessageEvents.COMMAND.register(cmd -> {
            String c = cmd.trim().toLowerCase();
            if (c.equals("bal") || c.equals("balance")) {
                awaiting = true;
                requestedAt = System.currentTimeMillis();
            }
        });

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!awaiting || overlay) return;
            if (System.currentTimeMillis() - requestedAt > 5000) { awaiting = false; return; }
            Double v = parse(msg.getString());
            if (v == null) return;
            balance = v;
            if (start < 0) start = v;
            awaiting = false;
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("balancehud", "balance"), (g, dt) -> render(g));
    }

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

    static String abbr(double n) {
        double a = Math.abs(n);
        String sign = n < 0 ? "-" : "+";
        if (a >= 1e12) return sign + String.format("$%.2fT", a / 1e12);
        if (a >= 1e9) return sign + String.format("$%.2fB", a / 1e9);
        if (a >= 1e6) return sign + String.format("$%.2fM", a / 1e6);
        if (a >= 1e3) return sign + String.format("$%.1fK", a / 1e3);
        return sign + "$" + Math.round(a);
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        Font f = mc.font;

        String amount = balance < 0 ? "$---" : String.format("$%,d", (long) balance);
        int w = Math.max(f.width(amount) * 2, f.width(NAME.toUpperCase())) + 24;
        w = Math.max(w, 150);
        int h = 52;

        g.fill(X, Y, X + w, Y + h, 0xC80E1016);
        g.outline(X, Y, w, h, 0x24FFFFFF);

        g.text(f, NAME.toUpperCase(), X + 10, Y + 8, 0xFFF2F3F7, true);

        g.pose().pushMatrix();
        g.pose().translate(X + 10, Y + 19);
        g.pose().scale(2f, 2f);
        g.text(f, amount, 0, 0, 0xFF3DF08A, true);
        g.pose().popMatrix();

        g.text(f, "BALANCE \u00B7 EST.", X + 10, Y + 40, 0xFF9AA0AD, false);

        if (balance >= 0 && start >= 0) {
            double d = balance - start;
            String t = abbr(d);
            g.text(f, t, X + w - 10 - f.width(t), Y + 40, d >= 0 ? 0xFF3DF08A : 0xFFFF4D6D, false);
        }
    }
}
