package fallen;

import arc.graphics.*;
import arc.struct.*;

public class Processor {
    static boolean[] used;



    // 1. Построение глобальной палитры (вызывается 1 раз на всю картинку).
    //    null — Quality 255, каждый цвет остаётся сам собой (палитра не нужна)
    public static Palette buildGlobalPalette(Pixmap src) {
        int raw = 255 - Main.coreQuality;
        if (raw <= 0) return null;
        // Сливаются цвета с diff < threshold, поэтому порог 0 или 1 ничего не сливает.
        // +2: уже на 254 сливаются соседние цвета, и каждое деление ползунка что-то меняет
        int threshold = 2 + (int)(Math.pow(raw, 1.5) / 30f);

        // Множитель 1.5 для HSV — для более тонкой настройки
        Palette palette = new Palette(Main.coreHsv, Main.coreHsv ? (threshold / 255f) * 1.5f : threshold);
        IntSet seen = new IntSet();

        for (int x = 0; x < src.width; x++) {
            for (int y = 0; y < src.height; y++) {
                int pixel = src.get(x, y);
                // Повтор цвета заведомо найдёт уже обработанную запись
                if (!seen.add(pixel)) continue;

                palette.setCoords(pixel);
                if (palette.find(false) < 0) palette.add(pixel);
            }
        }
        return palette;
    }

    private static float hueDiff(float h1, float h2) {
        float d = Math.abs(h1 - h2);
        return d > 0.5f ? 1.0f - d : d;
    }

    // 2. Применение палитры к куску (без повторной индексации)
    public static void applyPalette(Pixmap dst, Palette palette) {
        IntIntMap cache = new IntIntMap();

        for (int x = 0; x < dst.width; x++) {
            for (int y = 0; y < dst.height; y++) {
                int pixel = dst.get(x, y);
                int index = cache.get(pixel, -1);

                if (index < 0) {
                    palette.setCoords(pixel);
                    index = palette.find(true);
                    cache.put(pixel, index);
                }
                dst.set(x, y, palette.colors.get(index));
            }
        }
    }

    // Палитра с пространственной сеткой (ячейка >= порога). Совпадение (diff < порог)
    // может лежать только в соседней ячейке, поэтому вся палитра не перебирается.
    // Результат тот же, что у полного перебора.
    public static class Palette {
        static final int K = 512; // > максимального индекса ячейки по оси

        final boolean hsv;
        final float threshold;
        final int hueCells; // для HSV: оттенок закольцован, ширина ячейки 1/hueCells >= порога
        final IntSeq colors = new IntSeq();
        final FloatSeq coords = new FloatSeq();
        final IntMap<IntSeq> grid = new IntMap<>();

        final Color t = new Color();
        float c0, c1, c2;

        Palette(boolean hsv, float threshold) {
            this.hsv = hsv;
            this.threshold = threshold;
            this.hueCells = hsv ? Math.max(1, (int)(1f / threshold)) : 0;
        }

        void setCoords(int pixel) {
            if (hsv) {
                t.set(pixel);
                c0 = t.hue() / 360f;
                c1 = t.saturation();
                c2 = t.value();
            } else {
                c0 = (pixel >> 24) & 0xff;
                c1 = (pixel >> 16) & 0xff;
                c2 = (pixel >> 8) & 0xff;
            }
        }

        int cell0(float c) {
            return hsv ? (int)(c * hueCells) % hueCells : (int)(c / threshold);
        }

        int cell(float c) {
            return (int)(c / threshold);
        }

        void add(int pixel) {
            int index = colors.size;
            colors.add(pixel);
            coords.add(c0, c1, c2);
            int key = (cell0(c0) * K + cell(c1)) * K + cell(c2);
            IntSeq bucket = grid.get(key);
            if (bucket == null) grid.put(key, bucket = new IntSeq());
            bucket.add(index);
        }

        float diff(int index) {
            float[] c = coords.items;
            float d0 = hsv ? hueDiff(c0, c[index * 3]) : Math.abs(c0 - c[index * 3]);
            return d0 + Math.abs(c1 - c[index * 3 + 1]) + Math.abs(c2 - c[index * 3 + 2]);
        }

        // nearest = false: любая запись ближе порога; true: ближайшая (при равенстве — с меньшим индексом)
        int find(boolean nearest) {
            int i0 = cell0(c0), i1 = cell(c1), i2 = cell(c2);
            int span0 = hsv && hueCells < 3 ? hueCells : 3;
            int best = -1;
            float bestDiff = Float.MAX_VALUE;

            for (int a = 0; a < span0; a++) {
                int n0 = !hsv ? i0 - 1 + a : hueCells < 3 ? a : (i0 - 1 + a + hueCells) % hueCells;
                if (n0 < 0) continue;
                for (int n1 = i1 - 1; n1 <= i1 + 1; n1++) {
                    if (n1 < 0) continue;
                    for (int n2 = i2 - 1; n2 <= i2 + 1; n2++) {
                        if (n2 < 0) continue;
                        IntSeq bucket = grid.get((n0 * K + n1) * K + n2);
                        if (bucket == null) continue;

                        for (int j = 0; j < bucket.size; j++) {
                            int index = bucket.items[j];
                            float d = diff(index);
                            if (!nearest) {
                                if (d < threshold) return index;
                            } else if (d < bestDiff || (d == bestDiff && index < best)) {
                                bestDiff = d;
                                best = index;
                            }
                        }
                    }
                }
            }

            // Цвета не из исходной картинки могут не иметь соседей — полный перебор
            if (nearest && best < 0) {
                for (int index = 0; index < colors.size; index++) {
                    float d = diff(index);
                    if (d < bestDiff) {
                        bestDiff = d;
                        best = index;
                    }
                }
            }
            return best;
        }
    }

    // 0. Убираем прозрачность ДО палитры, иначе прозрачный пиксель
    //    получит альфу и цвет случайной записи палитры
    public static void flattenAlpha(Pixmap work) {
        Color tmpColor = new Color();
        Color gray = Color.valueOf("3d3d43");
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
                    tmpColor.lerp(gray, 1f - oldA);
                } else {
                    tmpColor.r *= a;
                    tmpColor.g *= a;
                    tmpColor.b *= a;
                }
                tmpColor.a = 1f;
                work.set(x, y, tmpColor.rgba8888());
            }
        }
    }

    // 3. Только группировка прямоугольников (без индексации!)
    public static ObjectMap<String, Seq<RectInt>> groupOnly(Pixmap work) {
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