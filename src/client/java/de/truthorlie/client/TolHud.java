package de.truthorlie.client;

import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import de.truthorlie.Phase;

/**
 * Das HUD: ein schmales Pill oben mittig (Phase + Timer), darunter eine kleine Hinweiszeile,
 * die eigene Karte als Chip, eine kompakte Rangliste oben links (nur in den Lobby-Phasen)
 * und große Banner für Ereignisse. Alles halbtransparent und weit weg vom Fadenkreuz.
 */
public final class TolHud {
	private TolHud() {
	}

	private static final int TEXT = 0xFFF5F5F5;
	private static final int MUTED = 0xFFA3A3A3;
	private static final int PANEL = 0xA8101418;
	private static final int TRUE_COLOR = 0xFF4ADE80;
	private static final int LIE_COLOR = 0xFFF87171;
	private static final int GOLD = 0xFFFBBF24;

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientState.hudEnabled) {
			return;
		}
		Font font = mc.font;
		if (ClientState.phase != Phase.IDLE) {
			int y = drawPill(g, font);
			y = drawHint(g, font, y);
			drawCardChip(g, font, y);
			drawBoard(g, font);
		}
		drawBanner(g, font);
	}

	// ------------------------------------------------------------------ Helfer

	private static void text(GuiGraphicsExtractor g, Font font, String s, float x, float y, int color, float scale) {
		if (scale == 1f) {
			g.text(font, s, Math.round(x), Math.round(y), color);
			return;
		}
		g.pose().pushMatrix();
		g.pose().scale(scale);
		g.text(font, s, Math.round(x / scale), Math.round(y / scale), color);
		g.pose().popMatrix();
	}

	private static float width(Font font, String s, float scale) {
		return font.width(s) * scale;
	}

	private static void centered(GuiGraphicsExtractor g, Font font, String s, float cx, float y, int color, float scale) {
		text(g, font, s, cx - width(font, s, scale) / 2f, y, color, scale);
	}

	/** Abgerundetes Rechteck (1px-Ecken weggelassen). */
	private static void panel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		g.fill(x1 + 1, y1, x2 - 1, y2, color);
		g.fill(x1, y1 + 1, x2, y2 - 1, color);
	}

	private static int withAlpha(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static String time(int ticks) {
		int s = (ticks + 19) / 20;
		return String.format("%d:%02d", s / 60, s % 60);
	}

	// ------------------------------------------------------------------ Pill (Phase + Timer)

	private static int drawPill(GuiGraphicsExtractor g, Font font) {
		Phase p = ClientState.phase;
		int remaining = ClientState.remainingTicks();
		String label = ClientState.paused ? "PAUSE" : p.label;
		String time = time(remaining);
		boolean urgent = !ClientState.paused && (p == Phase.ADVENTURE || p == Phase.VOTE || p == Phase.STORY) && remaining <= 200 && remaining > 0;
		boolean blink = urgent && (ClientState.tick % 10) < 5;
		int accent = urgent ? (blink ? LIE_COLOR : GOLD) : p.color;

		int w = Math.max(118, 22 + font.width(label) + 18 + font.width(time));
		int h = 17;
		int cx = g.guiWidth() / 2;
		int x1 = cx - w / 2;
		int y1 = 4;

		panel(g, x1, y1, x1 + w, y1 + h, PANEL);
		g.fill(x1 + 1, y1 + 3, x1 + 3, y1 + h - 3, accent);
		g.text(font, label, x1 + 9, y1 + 5, accent);
		g.text(font, time, x1 + w - 8 - font.width(time), y1 + 5, TEXT);

		// Fortschrittsbalken
		int barY = y1 + h + 1;
		g.fill(x1 + 2, barY, x1 + w - 2, barY + 2, 0x40FFFFFF);
		float frac = Math.max(0f, Math.min(1f, remaining / (float) ClientState.total));
		g.fill(x1 + 2, barY, x1 + 2 + Math.round((w - 4) * frac), barY + 2, accent);
		return barY + 5;
	}

	private static int drawHint(GuiGraphicsExtractor g, Font font, int y) {
		String hint = hintText();
		if (hint.isEmpty()) {
			return y;
		}
		float cx = g.guiWidth() / 2f;
		float scale = 0.8f;
		int pad = 4;
		float w = width(font, hint, scale);
		panel(g, Math.round(cx - w / 2f - pad), y - 1, Math.round(cx + w / 2f + pad), y + 9, 0x70101418);
		centered(g, font, hint, cx, y + 1, MUTED, scale);
		return y + 13;
	}

	private static String hintText() {
		if (ClientState.paused) {
			return "Das Spiel ist pausiert";
		}
		String rounds = ClientState.round > 0 ? "Runde " + ClientState.round + "/" + ClientState.totalRounds : "";
		return switch (ClientState.phase) {
			case COUNTDOWN -> rounds + "  ·  Gleich wirst du teleportiert";
			case ARRIVAL -> "Schau dich um ... deine Karte wird gezogen";
			case ADVENTURE -> rounds + "  ·  Erlebe etwas - du musst später erzählen";
			case STORY -> ClientState.isTeller ? "Du erzählst  ·  Fertig? Rechtsklick auf die Uhr" : "Erzähler: " + ClientState.teller;
			case VOTE -> {
				if (ClientState.isTeller) {
					yield "Die anderen stimmen ab  ·  " + ClientState.votesIn + "/" + ClientState.votersTotal;
				}
				if (ClientState.ownVote == 0) {
					yield "Rechtsklick: grün = WAHR  ·  rot = LÜGE  ·  " + ClientState.votesIn + "/" + ClientState.votersTotal;
				}
				yield "Du hast " + (ClientState.ownVote == 1 ? "WAHR" : "LÜGE") + " gewählt  ·  " + ClientState.votesIn + "/" + ClientState.votersTotal;
			}
			case REVEAL -> "Punkte werden verteilt";
			case ROUND_END -> rounds + " abgeschlossen";
			case GAME_END -> "Danke fürs Mitspielen!";
			default -> "";
		};
	}

	// ------------------------------------------------------------------ Eigene Karte

	private static void drawCardChip(GuiGraphicsExtractor g, Font font, int y) {
		if (ClientState.ownCard == 0) {
			return;
		}
		boolean truth = ClientState.ownCard == 1;
		int color = truth ? TRUE_COLOR : LIE_COLOR;
		String a = "DEINE KARTE";
		String b = truth ? "WAHR" : "LÜGE";
		float scale = 0.85f;
		float w = width(font, a, scale) + 8 + width(font, b, scale) + 14;
		float cx = g.guiWidth() / 2f;
		int x1 = Math.round(cx - w / 2f);
		int x2 = Math.round(cx + w / 2f);
		panel(g, x1, y, x2, y + 13, PANEL);
		g.fill(x1 + 1, y + 2, x1 + 3, y + 11, color);
		text(g, font, a, x1 + 7, y + 3, MUTED, scale);
		text(g, font, b, x1 + 7 + width(font, a, scale) + 6, y + 3, color, scale);
	}

	// ------------------------------------------------------------------ Rangliste (oben links)

	private static boolean showBoard() {
		return switch (ClientState.phase) {
			case COUNTDOWN, STORY, VOTE, REVEAL, ROUND_END, GAME_END -> true;
			default -> false;
		};
	}

	private static void drawBoard(GuiGraphicsExtractor g, Font font) {
		if (!ClientState.scoresEnabled) {
			return;
		}
		int x = 6;
		int y = 6;
		float s = 0.85f;
		if (!showBoard()) {
			// Im Abenteuer nur ein winziger Punktestand, damit nichts stört.
			if (ClientState.participant) {
				String t = "Punkte: " + ClientState.ownPoints;
				int w = Math.round(width(font, t, s)) + 10;
				panel(g, x, y, x + w, y + 12, 0x70101418);
				text(g, font, t, x + 5, y + 3, MUTED, s);
			}
			return;
		}
		List<ClientState.Row> rows = ClientState.board;
		if (rows.isEmpty()) {
			return;
		}
		int shown = Math.min(6, rows.size());
		float nameW = 40;
		for (int i = 0; i < shown; i++) {
			nameW = Math.max(nameW, width(font, shorten(rows.get(i).name()), s));
		}
		int w = Math.round(nameW) + 46;
		int rowH = 10;
		int h = 14 + shown * rowH + 3;
		panel(g, x, y, x + w, y + h, PANEL);
		g.fill(x + 1, y + 11, x + w - 1, y + 12, 0x30FFFFFF);
		text(g, font, "PUNKTE", x + 6, y + 3, MUTED, 0.75f);
		String me = Minecraft.getInstance().player.getName().getString();
		for (int i = 0; i < shown; i++) {
			ClientState.Row r = rows.get(i);
			int ry = y + 15 + i * rowH;
			boolean mine = r.name().equals(me);
			int col = mine ? GOLD : (i == 0 ? TEXT : MUTED);
			text(g, font, (i + 1) + ".", x + 6, ry, MUTED, s);
			text(g, font, shorten(r.name()), x + 18, ry, col, s);
			String pts = String.valueOf(r.points());
			text(g, font, pts, x + w - 6 - width(font, pts, s), ry, col, s);
		}
	}

	private static String shorten(String n) {
		return n.length() > 12 ? n.substring(0, 11) + "…" : n;
	}

	// ------------------------------------------------------------------ Banner

	private static void drawBanner(GuiGraphicsExtractor g, Font font) {
		if (ClientState.bannerTicks <= 0) {
			return;
		}
		long age = ClientState.tick - ClientState.bannerStart;
		if (age < 0 || age >= ClientState.bannerTicks) {
			return;
		}
		float alpha = Math.min(1f, Math.min(age / 6f, (ClientState.bannerTicks - age) / 12f));
		if (alpha * 255 < 8) {
			return;
		}
		String title = ClientState.bannerTitle;
		boolean number = title.length() <= 2;
		float scale = number ? 5f : 3f;
		float maxW = g.guiWidth() * 0.85f;
		while (scale > 1.2f && width(font, title, scale) > maxW) {
			scale -= 0.25f;
		}
		float cx = g.guiWidth() / 2f;
		float y = g.guiHeight() * 0.22f;
		int color = withAlpha(ClientState.bannerColor | 0xFF000000, alpha);
		centered(g, font, title, cx, y, color, scale);
		float titleH = 9 * scale;
		float lineW = Math.min(width(font, title, scale), maxW);
		int ly = Math.round(y + titleH + 3);
		g.fill(Math.round(cx - lineW / 2f), ly, Math.round(cx + lineW / 2f), ly + 2, color);
		if (!ClientState.bannerSub.isEmpty()) {
			centered(g, font, ClientState.bannerSub, cx, ly + 7, withAlpha(0xFFE5E5E5, alpha), 1.15f);
		}
	}
}
