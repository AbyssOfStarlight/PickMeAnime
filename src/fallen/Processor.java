package fallen;

import arc.graphics.*;
import arc.struct.*;

public class Processor {
    static boolean[] used;



    // 1. Построение глобальной палитры (вызывается 1 раз на всю картинку)
    public static void buildGlobalPalette(Pixmap src, Seq<Integer> rgbPalette, Seq<float[]> hsvPalette) {
        int raw = 255 - Main.coreQuality;
        int threshold = (int)(Math.pow(raw, 1.5) / 30f);
        Color t = new Color();

        for (int x = 0; x < src.width; x++) {
            for (int y = 0; y < src.height; y++) {
                int pixel = src.get(x, y);
                boolean found = false;

                if (Main.coreHsv) {
                    float hsvThreshold = (threshold / 255f) * 1.5f; // Множитель для более тонкой настройки

                    t.set(pixel);
                    float h = t.hue() / 360f;
                    float s = t.saturation();
                    float v = t.value();

                    for (int i = 0; i < hsvPalette.size; i++) {
                        float[] other = hsvPalette.get(i);
                        float diff = hueDiff(h, other[0]) + Math.abs(s - other[1]) + Math.abs(v - other[2]);

                        if (diff < hsvThreshold) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        rgbPalette.add(pixel);
                        hsvPalette.add(new float[]{h, s, v});
                    }
                } else {
                    int r1 = (pixel >> 24) & 0xff;
                    int g1 = (pixel >> 16) & 0xff;
                    int b1 = (pixel >> 8) & 0xff;

                    for (int other : rgbPalette) {
                        int r2 = (other >> 24) & 0xff;
                        int g2 = (other >> 16) & 0xff;
                        int b2 = (other >> 8) & 0xff;
                        int diff = Math.abs(r1-r2) + Math.abs(g1-g2) + Math.abs(b1-b2);

                        if (diff < threshold) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        rgbPalette.add(pixel);
                    }
                }
            }
        }
    }

    private static float hueDiff(float h1, float h2) {
        float d = Math.abs(h1 - h2);
        return d > 0.5f ? 1.0f - d : d;
    }

    // 2. Применение палитры к куску (без повторной индексации)
    public static void applyPalette(Pixmap dst, Seq<Integer> rgbPalette, Seq<float[]> hsvPalette) {
        if (rgbPalette.size == 0) return;

        Color t = new Color();

        for (int x = 0; x < dst.width; x++) {
            for (int y = 0; y < dst.height; y++) {
                int pixel = dst.get(x, y);
                int bestColor = rgbPalette.get(0);
                float minDiff = Float.MAX_VALUE;

                if (Main.coreHsv) {
                    t.set(pixel);
                    float h = t.hue() / 360f, s = t.saturation(), v = t.value();

                    for (int i = 0; i < hsvPalette.size; i++) {
                        float[] other = hsvPalette.get(i);
                        float diff = hueDiff(h, other[0]) + Math.abs(s - other[1]) + Math.abs(v - other[2]);

                        if (diff < minDiff) {
                            minDiff = diff;
                            bestColor = rgbPalette.get(i);
                        }
                    }
                } else {
                    int r1 = (pixel >> 24) & 0xff;
                    int g1 = (pixel >> 16) & 0xff;
                    int b1 = (pixel >> 8) & 0xff;

                    for (int other : rgbPalette) {
                        int r2 = (other >> 24) & 0xff;
                        int g2 = (other >> 16) & 0xff;
                        int b2 = (other >> 8) & 0xff;

                        int diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);

                        if (diff < minDiff) {
                            minDiff = diff;
                            bestColor = other;
                        }
                    }
                }
                dst.set(x, y, bestColor);
            }
        }
    }


    // 3. Только группировка прямоугольников (без индексации!)
    public static ObjectMap<String, Seq<RectInt>> groupOnly(Pixmap pixmap) {
        Pixmap work = new Pixmap(pixmap.width, pixmap.height);
        work.draw(pixmap, 0, 0);

        // Альфа-блендинг (остаётся без изменений)
        Color tmpColor = new Color();
        for (int x = 0; x < work.width; x++) {
            for (int y = 0; y < work.height; y++) {
                int rgba = work.get(x, y);
                int alpha = rgba & 0xFF;
                if (alpha == 255) continue;

                tmpColor.set(rgba);
                float a = alpha / 255f;

                if (Main.coreUseGray) {
                    float oldA = tmpColor.a;
                    tmpColor.a = 1f;
                    tmpColor.lerp(Color.valueOf("3d3d43"), 1f - oldA);
                } else {
                    tmpColor.r *= a;
                    tmpColor.g *= a;
                    tmpColor.b *= a;
                }
                tmpColor.a = 1f;
                work.set(x, y, tmpColor.rgba8888());
            }
        }

        // Группировка (остаётся без изменений)
        int w = work.width, h = work.height;
        ObjectMap<String, Seq<RectInt>> out = new ObjectMap<>();
        used = new boolean[w * h];

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (used[x + y * w]) continue;

                int color = work.get(x, y);
                RectInt rect = new RectInt(x, y, 1, 1);
                expand(work, rect, color, w, h);

                String colorCmd = "draw color " + ((color >> 24) & 0xff) + " " +
                        ((color >> 16) & 0xff) + " " + ((color >> 8) & 0xff) + " 255";
                out.get(colorCmd, Seq::new).add(rect);
            }
        }

        work.dispose();
        return out;
    }

    static void expand(Pixmap pixmap, RectInt r, int color, int w, int h) {
        // 1. Пытаемся расширить прямоугольник вправо
        while (r.x + r.width < w) {
            boolean canExpand = true;
            for (int i = 0; i < r.height; i++) {
                int cx = r.x + r.width;
                int cy = r.y + i;

                // Если пиксель другого цвета или уже занят другим прямоугольником — стоп
                if (pixmap.get(cx, cy) != color || used[cx + cy * w]) {
                    canExpand = false;
                    break;
                }
            }
            if (canExpand) r.width++; else break;
        }

        // 2. Пытаемся расширить получившийся прямоугольник вниз
        while (r.y + r.height < h) {
            boolean canExpand = true;
            for (int i = 0; i < r.width; i++) {
                int cx = r.x + i;
                int cy = r.y + r.height;

                if (pixmap.get(cx, cy) != color || used[cx + cy * w]) {
                    canExpand = false;
                    break;
                }
            }
            if (canExpand) r.height++; else break;
        }

        // 3. Помечаем все пиксели внутри найденного прямоугольника как "использованные"
        for (int ix = r.x; ix < r.x + r.width; ix++) {
            for (int iy = r.y; iy < r.y + r.height; iy++) {
                used[ix + iy * w] = true;
            }
        }
    }
}