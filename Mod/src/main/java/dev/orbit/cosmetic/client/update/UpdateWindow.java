package dev.orbit.cosmetic.client.update;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The update window, shown by {@link UpdateCheck} in a process of its own while Minecraft waits: a new version of
 * Orbit is available, update now? Without an answer within 10 seconds, Minecraft goes on loading; once the
 * player does anything in the window (click, drag, scroll, a key), the countdown stops and Minecraft waits for the
 * answer. Updating downloads the new jar, checks it is the one Orbit Cloud announced and signed with the release
 * key, puts it into the mods folder and, once Minecraft has closed, deletes the old jar.
 *
 * <p>It looks like Orbit's menus (grid background, bevelled panel and buttons, Minecraft's font when the game
 * provided it) and draws everything itself, title bar included. It tells Minecraft what happens with lines on its
 * output: {@code WAIT} (the countdown stopped), {@code YES} (updating), then {@code UPDATED} or
 * {@code FAILED <reason>}; or {@code SKIP} (not now). Only the JDK is used here: this class runs on its own,
 * outside the game, and must stay a single class (lambdas only, no inner or anonymous classes).
 */
public final class UpdateWindow extends JComponent implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener, WindowListener {
	private static final int WAIT_SECONDS = 10;
	/** Started from the game (the player chose Update there), which keeps running until the player closes it. */
	private static final boolean FROM_GAME = Boolean.getBoolean("orbit.updateFromGame");
	private static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

	// Orbit's colours (see gui/ui/Theme and FlatButton).
	private static final int BACKGROUND = 0xFF141416;
	private static final int GRID = 0x0DFFFFFF;
	private static final int PANEL = 0xFF1C1C20;
	private static final int PANEL_HOVER = 0xFF2B2B31;
	private static final int BORDER = 0xFF2E2E35;
	private static final int TEXT = 0xFFECECEF;
	private static final int MUTED = 0xFFA3A3AD;
	private static final int FAINT = 0xFF6F6F7A;
	private static final int RED = 0xFFFF2626;
	private static final int EDGE = 0xFF0A0A0C;
	private static final int EDGE_HOT = 0xFFE6E6EA;
	private static final int PRIMARY = 0xFFC21E1E;
	private static final int PRIMARY_HOVER = 0xFFE02626;
	private static final int SECONDARY = 0xFF34343C;
	private static final int SECONDARY_HOVER = 0xFF42424B;
	private static final int GRID_SIZE = 20;

	// Layout, in GUI pixels (drawn SCALE times larger).
	private static final int WIDTH = 280;
	private static final int TITLE_HEIGHT = 16;
	private static final int MARGIN = 8;
	private static final int BUTTON_HEIGHT = 18;
	private static final int LINE = 10;

	/** Button ids. */
	private static final int NONE = 0;
	private static final int MINIMIZE = 1;
	private static final int CLOSE = 2;
	private static final int NOT_NOW = 3;
	private static final int UPDATE = 4;
	private static final int OK = 5;

	private final String current;
	private final String version;
	private final boolean required;
	private final URI download;
	private final String sha256;
	private final long size;
	private final String signature;
	private final String target;
	private final Path modsFolder;
	private final Path oldJar;
	private final long gamePid;
	private final int scale;

	/** Minecraft's font (glyphs 8x8 on a 16x16 sheet) and each glyph's width; null without it. */
	private BufferedImage fontSheet;
	private final int[] glyphWidths = new int[256];
	private final Map<Integer, BufferedImage> tintedSheets = new HashMap<>();
	private final Map<String, BufferedImage> icons = new HashMap<>();
	private final Map<String, BufferedImage> tintedIcons = new HashMap<>();

	private JFrame frame;
	private Timer timer;
	private int secondsLeft = WAIT_SECONDS;
	/** The countdown runs until the player does anything in the window. */
	private boolean counting = true;
	private boolean answered;
	/** 0 while asking, 1 downloading, 2 installed, 3 failed. */
	private int stage;
	private volatile int percent;
	private String heading;
	private String message;
	private int hovered = NONE;
	private int pressed = NONE;
	private Point dragFrom;
	private int height;

	private UpdateWindow(String[] args) {
		current = args[0];
		version = args[1];
		required = Boolean.parseBoolean(args[2]);
		download = URI.create(args[3]);
		sha256 = args[4];
		size = Long.parseLong(args[5]);
		signature = args[6];
		target = args[7];
		modsFolder = Path.of(args[8]);
		oldJar = args[9].equals("-") ? null : Path.of(args[9]);
		gamePid = Long.parseLong(args[10]);
		scale = screenHeight() >= 1400 ? 3 : 2;
		heading = "Orbit " + version + " is available";
		message = "You have " + current + "." + (required ? " This version can no longer use Orbit Cloud." : "")
			+ " Update now? Minecraft closes once the update is installed; start it again afterwards.";
	}

	private static int screenHeight() {
		try {
			return Toolkit.getDefaultToolkit().getScreenSize().height;
		} catch (java.awt.HeadlessException e) {
			return 0;
		}
	}

	/** Arguments: current version, new version, required, download URL, SHA-256, size, signature, target, mods folder, old jar (- for none), game PID. */
	public static void main(String[] args) {
		if (args.length != 11 || GraphicsEnvironment.isHeadless()) {
			tell("SKIP");
			System.exit(0);
		}
		UpdateWindow window = new UpdateWindow(args);
		SwingUtilities.invokeLater(window::open);
	}

	private static synchronized void tell(String line) {
		System.out.println(line);
		System.out.flush();
	}

	// ---------------------------------------------------------------- window

	private void open() {
		loadFont();
		for (String name : new String[] {"orbit", "close", "update", "check"}) {
			icons.put(name, image("/assets/orbit_cosmetic/textures/gui/sprites/icon/" + name + ".png"));
		}
		frame = new JFrame("Orbit update");
		frame.setUndecorated(true);
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(this);
		BufferedImage icon = image("/assets/orbit_cosmetic/icon.png");
		if (icon != null) {
			frame.setIconImage(icon);
		}
		setFocusable(true);
		addMouseListener(this);
		addMouseMotionListener(this);
		addMouseWheelListener(this);
		addKeyListener(this);
		frame.setContentPane(this);
		relayout();
		frame.setLocationRelativeTo(null);
		frame.setAlwaysOnTop(true);
		frame.setVisible(true);
		frame.toFront();
		requestFocusInWindow();

		timer = new Timer(1000, event -> {
			secondsLeft--;
			if (secondsLeft <= 0) {
				skip();
			} else {
				repaint();
			}
		});
		timer.start();
		testHooks();
	}

	/** Sizes the window to its text. */
	private void relayout() {
		int lines = wrap(message, panelWidth() - 16).size();
		int panelHeight = 8 + 9 + 6 + lines * LINE + 6 + 12;
		height = TITLE_HEIGHT + MARGIN + panelHeight + MARGIN + BUTTON_HEIGHT + MARGIN;
		setPreferredSize(new Dimension(WIDTH * scale, height * scale));
		if (frame != null) {
			frame.pack();
		}
		repaint();
	}

	private static int panelWidth() {
		return WIDTH - MARGIN * 2;
	}

	/**
	 * For automated tests only (set by {@link UpdateCheck} from the game's own -D options): {@code orbit.updateShot}
	 * saves a picture of the window, {@code orbit.updateAnswer} (yes or no) answers it after a moment.
	 */
	private void testHooks() {
		String shot = System.getProperty("orbit.updateShot");
		String answer = System.getProperty("orbit.updateAnswer");
		if (shot != null) {
			Timer later = new Timer(1200, event -> {
				BufferedImage image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = image.createGraphics();
				paint(graphics);
				graphics.dispose();
				try {
					ImageIO.write(image, "png", Path.of(shot).toFile());
				} catch (IOException ignored) {
					// only a test picture
				}
			});
			later.setRepeats(false);
			later.start();
		}
		if (answer != null) {
			Timer later = new Timer(2500, event -> {
				if (answer.equals("yes")) {
					update();
				} else {
					skip();
				}
			});
			later.setRepeats(false);
			later.start();
		}
	}

	/** The player did something in the window: Minecraft waits for the answer instead of counting down. */
	private void interacted() {
		if (counting && !answered) {
			counting = false;
			timer.stop();
			tell("WAIT");
			repaint();
		}
	}

	private void skip() {
		if (answered) {
			return;
		}
		answered = true;
		timer.stop();
		tell("SKIP");
		frame.dispose();
		System.exit(0);
	}

	private void update() {
		if (answered) {
			return;
		}
		answered = true;
		timer.stop();
		tell("YES");
		stage = 1;
		message = "Downloading Orbit " + version + "...";
		relayout();
		Thread worker = new Thread(this::install, "Orbit update");
		worker.setDaemon(false);
		worker.start();
	}

	private void press(int button) {
		switch (button) {
			case MINIMIZE -> frame.setState(Frame.ICONIFIED);
			case CLOSE -> {
				if (!answered) {
					skip();
				} else {
					frame.dispose();
				}
			}
			case NOT_NOW -> skip();
			case UPDATE -> update();
			case OK -> frame.dispose();
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.scale(scale, scale);
		fill(g, 0, 0, WIDTH, height, BACKGROUND);
		for (int x = 0; x < WIDTH; x += GRID_SIZE) {
			fill(g, x, 0, 1, height, GRID);
		}
		for (int y = 0; y < height; y += GRID_SIZE) {
			fill(g, 0, y, WIDTH, 1, GRID);
		}

		// Title bar: the Orbit icon and name, minimize and close; drag it to move the window.
		fill(g, 0, 0, WIDTH, TITLE_HEIGHT, PANEL);
		fill(g, 0, 0, WIDTH, 1, 0x1FFFFFFF);
		fill(g, 0, TITLE_HEIGHT - 1, WIDTH, 1, BORDER);
		icon(g, "orbit", 4, 3, RED);
		text(g, "Orbit", 16, 4, TEXT, false);
		int[] minimize = buttonBounds(MINIMIZE);
		int[] close = buttonBounds(CLOSE);
		if (hovered == MINIMIZE) {
			fill(g, minimize[0], minimize[1], minimize[2], minimize[3], PANEL_HOVER);
		}
		fill(g, minimize[0] + 4, minimize[1] + 8, 6, 1, hovered == MINIMIZE ? TEXT : MUTED);
		if (hovered == CLOSE) {
			fill(g, close[0], close[1], close[2], close[3], PRIMARY);
		}
		icon(g, "close", close[0] + 2, close[1] + 1, hovered == CLOSE ? TEXT : MUTED);

		// The panel with the question.
		int px = MARGIN;
		int py = TITLE_HEIGHT + MARGIN;
		int pw = panelWidth();
		int ph = height - py - MARGIN - BUTTON_HEIGHT - MARGIN;
		fill(g, px, py, pw, ph, PANEL);
		outline(g, px, py, pw, ph, BORDER);
		fill(g, px + 1, py + 1, pw - 2, 1, 0x1FFFFFFF);
		fill(g, px + 1, py + 2, 1, ph - 4, 0x12FFFFFF);
		fill(g, px + 1, py + ph - 2, pw - 2, 1, 0x59000000);
		fill(g, px + 1, py + 1, 2, ph - 2, RED);
		text(g, heading, px + 10, py + 8, TEXT, false);
		int y = py + 8 + 9 + 6;
		for (String line : wrap(message, pw - 16)) {
			text(g, line, px + 10, y, MUTED, false);
			y += LINE;
		}
		int footY = py + ph - 14;
		switch (stage) {
			case 0 -> text(g, counting ? "Minecraft goes on loading in " + secondsLeft + " s" : "Minecraft waits for your answer", px + 10, footY, FAINT, false);
			case 1 -> {
				int barWidth = pw - 20;
				fill(g, px + 10, footY + 2, barWidth, 4, 0xFF0A0A0C);
				fill(g, px + 10, footY + 2, barWidth * percent / 100, 4, RED);
			}
			default -> {
			}
		}

		// Buttons, Orbit style: raised, a dark edge (light when hovered), lit top and left, shaded bottom.
		if (stage == 0) {
			button(g, NOT_NOW, "Not now", null, SECONDARY, SECONDARY_HOVER);
			button(g, UPDATE, "Update", "update", PRIMARY, PRIMARY_HOVER);
		} else if (stage >= 2) {
			button(g, OK, "OK", "check", PRIMARY, PRIMARY_HOVER);
		}
		outline(g, 0, 0, WIDTH, height, BORDER);
		g.dispose();
	}

	/** x, y, width, height of a button, in GUI pixels. */
	private int[] buttonBounds(int id) {
		int y = height - MARGIN - BUTTON_HEIGHT;
		return switch (id) {
			case MINIMIZE -> new int[] {WIDTH - 34, 1, 16, TITLE_HEIGHT - 2};
			case CLOSE -> new int[] {WIDTH - 17, 1, 16, TITLE_HEIGHT - 2};
			case UPDATE, OK -> new int[] {WIDTH - MARGIN - 76, y, 76, BUTTON_HEIGHT};
			case NOT_NOW -> new int[] {WIDTH - MARGIN - 76 - 4 - 64, y, 64, BUTTON_HEIGHT};
			default -> null;
		};
	}

	private void button(Graphics2D g, int id, String label, String icon, int fill, int hover) {
		int[] b = buttonBounds(id);
		boolean hot = hovered == id;
		int color = hot ? hover : fill;
		fill(g, b[0], b[1], b[2], b[3], hot ? EDGE_HOT : EDGE);
		fill(g, b[0] + 1, b[1] + 1, b[2] - 2, b[3] - 2, color);
		fill(g, b[0] + 1, b[1] + 1, b[2] - 2, 1, shade(color, 1.35F));
		fill(g, b[0] + 1, b[1] + 2, 1, b[3] - 5, shade(color, 1.15F));
		fill(g, b[0] + 1, b[1] + b[3] - 3, b[2] - 2, 2, shade(color, 0.62F));
		int sink = pressed == id ? 0 : 1;
		int contentWidth = textWidth(label) + (icon != null ? 12 : 0);
		int x = b[0] + (b[2] - contentWidth) / 2;
		int y = b[1] + (b[3] - 8) / 2 - sink;
		if (icon != null) {
			icon(g, icon, x, b[1] + (b[3] - 9) / 2 - sink, TEXT);
			x += 12;
		}
		text(g, label, x, y, TEXT, true);
	}

	private static void fill(Graphics2D g, int x, int y, int width, int height, int argb) {
		g.setColor(new Color(argb, true));
		g.fillRect(x, y, width, height);
	}

	private static void outline(Graphics2D g, int x, int y, int width, int height, int argb) {
		fill(g, x, y, width, 1, argb);
		fill(g, x, y + height - 1, width, 1, argb);
		fill(g, x, y, 1, height, argb);
		fill(g, x + width - 1, y, 1, height, argb);
	}

	private static int shade(int color, float factor) {
		int r = Math.min(255, Math.round((color >> 16 & 0xFF) * factor));
		int gr = Math.min(255, Math.round((color >> 8 & 0xFF) * factor));
		int b = Math.min(255, Math.round((color & 0xFF) * factor));
		return color & 0xFF000000 | r << 16 | gr << 8 | b;
	}

	/** A 9x9 white icon, tinted. */
	private void icon(Graphics2D g, String name, int x, int y, int color) {
		BufferedImage base = icons.get(name);
		if (base == null) {
			return;
		}
		BufferedImage tinted = tintedIcons.computeIfAbsent(name + ":" + color, key -> tint(base, color));
		g.drawImage(tinted, x, y, 9, 9, null);
	}

	private static BufferedImage tint(BufferedImage source, int color) {
		BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				int pixel = source.getRGB(x, y);
				int alpha = (pixel >>> 24) * (color >>> 24) / 255;
				int r = (pixel >> 16 & 0xFF) * (color >> 16 & 0xFF) / 255;
				int gr = (pixel >> 8 & 0xFF) * (color >> 8 & 0xFF) / 255;
				int b = (pixel & 0xFF) * (color & 0xFF) / 255;
				result.setRGB(x, y, alpha << 24 | r << 16 | gr << 8 | b);
			}
		}
		return result;
	}

	private static BufferedImage image(String resource) {
		try (InputStream in = UpdateWindow.class.getResourceAsStream(resource)) {
			return in == null ? null : ImageIO.read(in);
		} catch (IOException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- text

	/** Minecraft's ASCII font sheet, copied out of the game by {@link UpdateCheck}; without it a system font is used. */
	private void loadFont() {
		BufferedImage sheet = image("/assets/minecraft/textures/font/ascii.png");
		if (sheet == null || sheet.getWidth() < 128 || sheet.getWidth() != sheet.getHeight()) {
			return;
		}
		BufferedImage normalized = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = normalized.createGraphics();
		g.drawImage(sheet, 0, 0, 128, 128, null);
		g.dispose();
		for (int c = 0; c < 256; c++) {
			int cx = c % 16 * 8;
			int cy = c / 16 * 8;
			int width = 0;
			for (int x = 0; x < 8; x++) {
				for (int y = 0; y < 8; y++) {
					if ((normalized.getRGB(cx + x, cy + y) >>> 24) > 0) {
						width = x + 1;
					}
				}
			}
			glyphWidths[c] = width;
		}
		fontSheet = normalized;
	}

	private int advance(char c) {
		if (c == ' ') {
			return 4;
		}
		return c < 256 && glyphWidths[c] > 0 ? glyphWidths[c] + 1 : 6;
	}

	private int textWidth(String text) {
		if (fontSheet == null) {
			return text.length() * 6;
		}
		int width = 0;
		for (int i = 0; i < text.length(); i++) {
			width += advance(text.charAt(i));
		}
		return width - 1;
	}

	/** Draws text with its top left corner at x, y (GUI pixels), with Minecraft's drop shadow if asked. */
	private void text(Graphics2D g, String text, int x, int y, int color, boolean shadow) {
		if (shadow) {
			text(g, text, x + 1, y + 1, (color & 0xFCFCFC) >> 2 | color & 0xFF000000, false);
		}
		if (fontSheet == null) {
			g.setColor(new Color(color, true));
			g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 8));
			g.drawString(text, x, y + 7);
			return;
		}
		BufferedImage sheet = tintedSheets.computeIfAbsent(color, key -> tint(fontSheet, color));
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			int glyph = c < 256 && glyphWidths[c] > 0 ? c : '?';
			if (c != ' ') {
				int sx = glyph % 16 * 8;
				int sy = glyph / 16 * 8;
				g.drawImage(sheet, x, y, x + 8, y + 8, sx, sy, sx + 8, sy + 8, null);
			}
			x += advance(c);
		}
	}

	/** Splits text into lines no wider than {@code width} GUI pixels. */
	private List<String> wrap(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (textWidth(candidate) > width && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	// ---------------------------------------------------------------- input

	private int buttonAt(MouseEvent event) {
		int x = event.getX() / scale;
		int y = event.getY() / scale;
		List<Integer> visible = new ArrayList<>(List.of(MINIMIZE, CLOSE));
		if (stage == 0) {
			visible.add(NOT_NOW);
			visible.add(UPDATE);
		} else if (stage >= 2) {
			visible.add(OK);
		}
		for (int id : visible) {
			int[] b = buttonBounds(id);
			if (x >= b[0] && y >= b[1] && x < b[0] + b[2] && y < b[1] + b[3]) {
				return id;
			}
		}
		return NONE;
	}

	@Override
	public void mousePressed(MouseEvent event) {
		interacted();
		requestFocusInWindow();
		pressed = buttonAt(event);
		dragFrom = pressed == NONE && event.getY() / scale < TITLE_HEIGHT ? event.getPoint() : null;
		repaint();
	}

	@Override
	public void mouseReleased(MouseEvent event) {
		int released = buttonAt(event);
		int wasPressed = pressed;
		pressed = NONE;
		dragFrom = null;
		repaint();
		if (released != NONE && released == wasPressed) {
			press(released);
		}
	}

	@Override
	public void mouseDragged(MouseEvent event) {
		interacted();
		if (dragFrom != null) {
			Point on = event.getLocationOnScreen();
			frame.setLocation(on.x - dragFrom.x, on.y - dragFrom.y);
		}
	}

	@Override
	public void mouseMoved(MouseEvent event) {
		int now = buttonAt(event);
		if (now != hovered) {
			hovered = now;
			repaint();
		}
	}

	@Override
	public void mouseExited(MouseEvent event) {
		hovered = NONE;
		repaint();
	}

	@Override
	public void mouseWheelMoved(MouseWheelEvent event) {
		interacted();
	}

	@Override
	public void keyPressed(KeyEvent event) {
		interacted();
		if (stage == 0 && event.getKeyCode() == KeyEvent.VK_ENTER) {
			update();
		} else if (stage == 0 && event.getKeyCode() == KeyEvent.VK_ESCAPE) {
			skip();
		} else if (stage >= 2 && (event.getKeyCode() == KeyEvent.VK_ENTER || event.getKeyCode() == KeyEvent.VK_ESCAPE)) {
			frame.dispose();
		}
	}

	@Override
	public void mouseClicked(MouseEvent event) {
	}

	@Override
	public void mouseEntered(MouseEvent event) {
	}

	@Override
	public void keyTyped(KeyEvent event) {
	}

	@Override
	public void keyReleased(KeyEvent event) {
	}

	/** Closing the window another way (Alt+F4): like the close button. */
	@Override
	public void windowClosing(WindowEvent event) {
		press(CLOSE);
	}

	@Override
	public void windowOpened(WindowEvent event) {
	}

	@Override
	public void windowClosed(WindowEvent event) {
	}

	@Override
	public void windowIconified(WindowEvent event) {
	}

	@Override
	public void windowDeiconified(WindowEvent event) {
	}

	@Override
	public void windowActivated(WindowEvent event) {
	}

	@Override
	public void windowDeactivated(WindowEvent event) {
	}

	// ---------------------------------------------------------------- installing

	/** Downloads, checks and installs the new jar, then deletes the old one once Minecraft has closed. */
	private void install() {
		Path part = modsFolder.resolve(".orbit-mod-" + version + ".part");
		try {
			byte[] jar = downloadJar();
			String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
			if (jar.length != size || !actual.equals(sha256)) {
				throw new IOException("the download is not the file Orbit Cloud announced");
			}
			if (!isNewer(version, current) || !signedByOrbit(actual)) {
				throw new IOException("the update is not signed by Orbit");
			}
			Files.write(part, jar);
			Path installed = modsFolder.resolve("orbit-mod-mc" + target + "-" + version + ".jar");
			Files.move(part, installed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			tell("UPDATED");
			SwingUtilities.invokeLater(() -> {
				stage = 2;
				heading = "Orbit " + version + " is installed";
				message = FROM_GAME ? "Close Minecraft and start it again to play with the new version."
					: "Minecraft is closing. Start it again to play with the new version.";
				relayout();
			});
			deleteOldJar(installed);
		} catch (Exception e) {
			try {
				Files.deleteIfExists(part);
			} catch (IOException ignored) {
				// nothing left to clean up
			}
			String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			tell("FAILED " + reason);
			SwingUtilities.invokeLater(() -> {
				stage = 3;
				heading = "The update failed";
				message = "Reason: " + reason + ". Minecraft goes on loading with " + current + ".";
				relayout();
			});
		}
	}

	private byte[] downloadJar() throws IOException, InterruptedException {
		if (size <= 0 || size > MAX_JAR_BYTES) {
			throw new IOException("the update has an unexpected size");
		}
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(download).timeout(Duration.ofMinutes(2)).header("User-Agent", "OrbitCosmetic/" + current + " updater").GET().build();
		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new IOException("Orbit Cloud answered " + response.statusCode());
		}
		try (InputStream in = response.body(); var out = new java.io.ByteArrayOutputStream((int) size)) {
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int read;
			while ((read = in.read(buffer)) >= 0) {
				total += read;
				if (total > size) {
					throw new IOException("the download is larger than announced");
				}
				out.write(buffer, 0, read);
				percent = (int) (total * 100 / size);
				repaint();
			}
			return out.toByteArray();
		}
	}

	/** Whether the release's signature was made with the release key, whose public half is built into the mod. */
	private boolean signedByOrbit(String actualSha256) {
		try (InputStream in = UpdateWindow.class.getResourceAsStream("/orbit-release.pub")) {
			if (in == null) {
				return false;
			}
			byte[] encoded = Base64.getDecoder().decode(new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim());
			PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(key);
			verifier.update(("orbit-release\n" + version + "\n" + target + "\n" + actualSha256).getBytes(StandardCharsets.UTF_8));
			return verifier.verify(Base64.getDecoder().decode(signature));
		} catch (Exception e) {
			return false;
		}
	}

	/** True when {@code a} (such as 0.2.1) is a later version than {@code b}. */
	static boolean isNewer(String a, String b) {
		int[] x = parse(a);
		int[] y = parse(b);
		if (x == null || y == null) {
			return false;
		}
		for (int i = 0; i < 3; i++) {
			if (x[i] != y[i]) {
				return x[i] > y[i];
			}
		}
		return false;
	}

	static int[] parse(String version) {
		String core = version == null ? "" : version.split("[+-]", 2)[0];
		String[] parts = core.split("\\.");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The running game keeps its jar open (on Windows, locked), so it is deleted once the game has closed. */
	private void deleteOldJar(Path installed) {
		if (oldJar != null && !oldJar.equals(installed)) {
			ProcessHandle.of(gamePid).ifPresent(game -> {
				try {
					// Updated from the game: the player closes it when they like (the old jar stays until then).
					if (FROM_GAME) {
						game.onExit().get();
					} else {
						game.onExit().get(2, TimeUnit.MINUTES);
					}
				} catch (Exception e) {
					// try anyway
				}
			});
			for (int attempt = 0; attempt < 30; attempt++) {
				try {
					Files.deleteIfExists(oldJar);
					break;
				} catch (IOException e) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException interrupted) {
						break;
					}
				}
			}
		}
		// Ends this process once the window is closed.
		while (frame.isDisplayable()) {
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				break;
			}
		}
		System.exit(0);
	}
}
