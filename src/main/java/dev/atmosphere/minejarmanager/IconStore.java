package dev.atmosphere.minejarmanager;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads, scales and caches icons off the EDT. Unloadable icons become a coloured letter tile. */
final class IconStore {
    static final int SIZE = 36;

    interface Source { byte[] load() throws Exception; }

    private final Map<String, Icon> cache = new ConcurrentHashMap<>();
    private final Map<String, Icon> placeholders = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "icon-loader");
        t.setDaemon(true);
        return t;
    });

    Icon url(String url, String name, Runnable onLoaded) {
        return get(url, () -> {
            HttpResponse<byte[]> r = http.send(
                    HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "MineJarManager/1.0").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() < 400 ? r.body() : null;
        }, name, onLoaded);
    }

    Icon bytes(String key, byte[] data, String name, Runnable onLoaded) {
        return get(key, () -> data, name, onLoaded);
    }

    Icon get(String key, Source src, String name, Runnable onLoaded) {
        Icon c = cache.get(key);
        if (c != null) return c;
        if (loading.add(key)) {
            pool.execute(() -> {
                Icon ic = null;
                try {
                    byte[] data = src.load();
                    BufferedImage img = data == null ? null : javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) ic = new ImageIcon(scale(img));
                } catch (Exception ignored) {
                    // unsupported format (svg) or network error -> placeholder
                }
                cache.put(key, ic != null ? ic : placeholder(name));
                if (onLoaded != null) SwingUtilities.invokeLater(onLoaded);
            });
        }
        return placeholder(name);
    }

    static Image scale(BufferedImage src) {
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, SIZE, SIZE, 9, 9));
        g.drawImage(src, 0, 0, SIZE, SIZE, null);
        g.dispose();
        return out;
    }

    Icon placeholder(String name) {
        String key = name == null ? "?" : name;
        return placeholders.computeIfAbsent(key, k -> {
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            float hue = (Math.abs(k.hashCode()) % 360) / 360f;
            g.setColor(Color.getHSBColor(hue, 0.40f, 0.60f));
            g.fillRoundRect(0, 0, SIZE, SIZE, 9, 9);
            g.setColor(new Color(255, 255, 255, 230));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            String s = k.isEmpty() ? "?" : k.substring(0, 1).toUpperCase(Locale.ROOT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, (SIZE - fm.stringWidth(s)) / 2, (SIZE - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
            return new ImageIcon(img);
        });
    }

    /** Wraps an icon so it paints faded (used for disabled plugins). */
    static Icon dim(Icon base) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                base.paintIcon(c, g2, x, y);
                g2.dispose();
            }
            public int getIconWidth() { return base.getIconWidth(); }
            public int getIconHeight() { return base.getIconHeight(); }
        };
    }
}
