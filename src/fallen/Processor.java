package fallen;

import arc.graphics.*;
import arc.struct.*;

public class Processor {
    static boolean[] used;

    public static ObjectMap<String, Seq<RectInt>> process(Pixmap pixmap) {
        // Создаем копию, чтобы не портить оригинал
        Pixmap work = new Pixmap(pixmap.width, pixmap.height);
        work.draw(pixmap, 0, 0);

        if (Main.coreQuality < 255) {
            index(work);
        }

        Color tmpColor = new Color(); // Один объект на весь цикл

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

        int w = work.width, h = work.height;
        ObjectMap<String, Seq<RectInt>> out = new ObjectMap<>();
        used = new boolean[w * h];

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (used[x + y * w]) continue;

                int color = work.get(x, y);
                RectInt rect = new RectInt(x, y, 1, 1);
                expand(work, rect, color, w, h);

                String colorCmd = "draw color " + ((color >> 24) & 0xff) + " " + ((color >> 16) & 0xff) + " " + ((color >> 8) & 0xff);
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

    static void index(Pixmap pixmap) {
        Seq<Integer> palette = new Seq<>();
        Seq<float[]> hsvPalette = new Seq<>();
        int quality = (255 - Main.coreQuality) * 3;
        Color t = new Color();

        for (int x = 0; x < pixmap.width; x++) {
            for (int y = 0; y < pixmap.height; y++) {
                int pixel = pixmap.get(x, y);
                boolean found = false;

                if (Main.coreHsv) {
                    t.set(pixel);
                    float h = t.hue(), s = t.saturation(), v = t.value();
                    for (int i = 0; i < hsvPalette.size; i++) {
                        float[] other = hsvPalette.get(i);
                        if (Math.abs(h - other[0])*360f + Math.abs(s - other[1]) + Math.abs(v - other[2]) < quality) {
                            pixmap.set(x, y, palette.get(i));
                            found = true; break;
                        }
                    }
                    if (!found) {
                        palette.add(pixel);
                        hsvPalette.add(new float[]{h, s, v});
                    }
                } else {
                    int r1 = (pixel >> 24) & 0xff, g1 = (pixel >> 16) & 0xff, b1 = (pixel >> 8) & 0xff;
                    for (int other : palette) {
                        int r2 = (other >> 24) & 0xff, g2 = (other >> 16) & 0xff, b2 = (other >> 8) & 0xff;
                        if (Math.abs(r1-r2) + Math.abs(g1-g2) + Math.abs(b1-b2) < quality) {
                            pixmap.set(x, y, other);
                            found = true; break;
                        }
                    }
                    if (!found) palette.add(pixel);
                }
            }
        }
    }
}