package fallen;

import arc.graphics.*;
import arc.math.Mathf;
import arc.struct.*;
import arc.util.Log;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.logic.LExecutor;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;

public class Exporter {
    static boolean[][] occupied;
    static final int MAP_SIZE = 800; // Размер виртуального поля
    static final int OFFSET = 300;   // Смещение, чтобы искать в "отрицательных" зонах
    static int centralDistLink = 10;
    static int edgeDistLink = 9;
    static int centralSideLimit = 1;
    static int failedProcessors;


    // Тяжёлая часть: вызывается из фонового потока, в мир/UI/GL не лезет
    public static Schematic export(Pixmap input) {
        Log.info("Export started: quality=" + Main.coreQuality + ", size=" + input.width + "x" + input.height);
        int size = Main.coreSize;
        int gridW = Main.coreGridW;
        int gridH = Main.coreGridH;
        int dBlockSize = Main.coreDisplay.size;
        boolean tiled = isTiledDisplay();

        occupied = new boolean[MAP_SIZE][MAP_SIZE];
        failedProcessors = 0;
        Seq<PendingTile> pending = new Seq<>();

        int fullW, fullH;
        if (tiled) {
            // Плитки сливаются в ОДИН холст с общими координатами (минус рамка по краям)
            int frame = ((TileableLogicDisplay) Main.coreDisplay).frameSize;
            fullW = size * gridW - frame * 2;
            fullH = size * gridH - frame * 2;
        } else {
            fullW = size * gridW;
            fullH = size * gridH;
        }

        Pixmap fullPixmap = new Pixmap(fullW, fullH);
        fullPixmap.fill(Color.rgba8888(0f, 0f, 0f, 1f));

        try {
            // 1. Изменяем размеры картинок и убираем прозрачность
            scaleAndDraw(input, fullPixmap, fullW, fullH);
            Processor.flattenAlpha(fullPixmap);

            // 2. Планируем сетку дисплеев
            creatDisplayGreed(gridW, gridH, dBlockSize, pending);

            //3. Создаём блоки схем
            if (tiled) {
                createTiledScheme(gridW, gridH, pending, fullPixmap);
            } else {
                creatDisplaySheme(size, gridW, gridH, dBlockSize, pending, fullPixmap);
            }

            // 4. Нормализация координат (левый нижний угол всех блоков -> 0,0)
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            for (PendingTile pt : pending) {
                minX = Math.min(minX, pt.x + pt.block.sizeOffset);
                minY = Math.min(minY, pt.y + pt.block.sizeOffset);
            }
            for (PendingTile pt : pending) {
                pt.x -= minX;
                pt.y -= minY;
                pt.linkX -= minX;
                pt.linkY -= minY;
            }

            // 5. Сборка финальной схемы
            return buildSchematic(gridW, gridH, pending);
        } finally {
            fullPixmap.dispose();
        }
    }

    // Сохранение и выбор схемы: только из главного потока (там UI и GL)
    public static void useSchematic(Schematic schem) {
        if(Main.saveGenerate) Vars.schematics.add(schem);
        Vars.ui.schematics.hide();
        Vars.control.input.useSchematic(schem);

        if (failedProcessors > 0) {
            Vars.ui.showInfo("[scarlet]" + failedProcessors + "[] processors could not be placed within link range.\nParts of the image will be missing - try a smaller grid or fewer colors.");
        }
    }

    private static Schematic buildSchematic(int gridW, int gridH, Seq<PendingTile> pending) {
        Seq<Schematic.Stile> tiles = new Seq<>();

        for (PendingTile pt : pending) {
            Object config = null;
            if (!pt.isDisplay) {
                // Линки в конфиге схемы хранятся относительно процессора
                config = LogicBlock.compress(pt.code, Seq.with(new LogicLink(pt.linkX - pt.x, pt.linkY - pt.y, "display1", true)));
            }
            tiles.add(new Schematic.Stile(pt.block, pt.x, pt.y, config, (byte)0));
        }

        int mw = 0, mh = 0;
        for (var s : tiles) {
            mw = Math.max(mw, s.x + s.block.sizeOffset + s.block.size);
            mh = Math.max(mh, s.y + s.block.sizeOffset + s.block.size);
        }

        StringMap tags = new StringMap();
        tags.put("name", "_PickMe " + gridW + "x" + gridH);
        tags.put("description", "Where hentai?");
        tags.put("labels", "[\"_arts\"]");
        Schematic schem = new Schematic(tiles, tags, mw, mh);
        schem.labels.add("_arts");
        return schem;
    }

    private static void creatDisplaySheme(int size, int gridW, int gridH, int dBlockSize, Seq<PendingTile> pending, Pixmap fullPixmap) {

        // 1. СОЗДАЕМ СПИСОК ДИСПЛЕЕВ С ПРИОРИТЕТОМ
        Seq<DisplayInfo> displays = new Seq<>();
        int centerX = (gridW - 1) / 2;  // Центр по X
        int centerY = (gridH - 1) / 2;  // Центр по Y

        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                // Manhattan distance от центра (чем меньше, тем ближе к центру)
                int dist = Math.abs(gx - centerX) + Math.abs(gy - centerY);
                displays.add(new DisplayInfo(gx, gy, dist));
            }
        }

        // Сортируем: сначала центральные дисплеи (меньший priority)
        displays.sort((d1, d2) -> Integer.compare(d1.priority, d2.priority));


        // 1. Сначала строим ОДНУ палитру на всю картинку
        Processor.Palette palette = Main.coreQuality < 255 ? Processor.buildGlobalPalette(fullPixmap) : null;
        // 2. Генерируем код и ищем место для процессоров
        for (DisplayInfo disp : displays) {
            int gx = disp.gx;
            int gy = disp.gy;
            int dispX = gx * dBlockSize;
            int dispY = gy * dBlockSize;

            boolean isCentral = gx > 0 && gx < gridW - 1 && gy > 0 && gy < gridH - 1;

            // Вырезаем кусок картинки для этого экрана
            Pixmap region = new Pixmap(size, size);
            region.draw(fullPixmap, gx * size, (gridH - 1 - gy) * size, size, size, 0, 0, size, size);

            // Применяем глобальную палитру -> цвета на стыках совпадут идеально
            if (palette != null) {
                Processor.applyPalette(region, palette);
            }
            // Только группируем в прямоугольники (без пересчёта палитры)
            ObjectMap<String, Seq<RectInt>> optimized = Processor.groupOnly(region);
            region.dispose();

            for (String code : generateCode(optimized, size)) {
                // Ищем место вплотную к дисплею
                Point pos = findAdjacentFree(dispX, dispY, dBlockSize);
                if (pos == null) {
                    // Если вплотную нет, ищем дальше (в пределах дальности линка)
                    pos = findNearestFree(dispX, dispY, dBlockSize, isCentral);
                }

                if (pos != null) {
                    int link = -Main.coreDisplay.sizeOffset;
                    addProcessor(pending, pos, dispX + link, dispY + link, code);
                } else {
                    failedProcessors++;
                    Log.warn("Не удалось разместить процессор для дисплея " + gx + "," + gy);
                }
            }
        }
    }

    private static void createTiledScheme(int gridW, int gridH, Seq<PendingTile> pending, Pixmap fullPixmap) {
        // Все плитки сливаются в один дисплей: процессор, прилинкованный к ЛЮБОЙ плитке,
        // рисует на общем холсте в общих координатах. Поэтому картинку не режем.
        Processor.Palette palette = Main.coreQuality < 255 ? Processor.buildGlobalPalette(fullPixmap) : null;
        if (palette != null) {
            Processor.applyPalette(fullPixmap, palette);
        }

        ObjectMap<String, Seq<RectInt>> optimized = Processor.groupOnly(fullPixmap);

        for (String code : generateCode(optimized, fullPixmap.height)) {
            // Ищем место кольцами вокруг всей стены и линкуем к ближайшей плитке
            Point pos = null;
            search:
            for (int r = 1; r <= 11; r++) {
                for (int x = -r; x < gridW + r; x++) {
                    if (canPlaceTiled(x, -r, gridW, gridH)) { pos = new Point(x, -r); break search; }
                    if (canPlaceTiled(x, gridH + r - 1, gridW, gridH)) { pos = new Point(x, gridH + r - 1); break search; }
                }
                for (int y = -r + 1; y < gridH + r - 1; y++) {
                    if (canPlaceTiled(-r, y, gridW, gridH)) { pos = new Point(-r, y); break search; }
                    if (canPlaceTiled(gridW + r - 1, y, gridW, gridH)) { pos = new Point(gridW + r - 1, y); break search; }
                }
            }

            if (pos != null) {
                addProcessor(pending, pos, Mathf.clamp(pos.x, 0, gridW - 1), Mathf.clamp(pos.y, 0, gridH - 1), code);
            } else {
                failedProcessors++;
                Log.warn("Не удалось разместить процессор для tile-дисплея");
            }
        }
    }

    private static boolean canPlaceTiled(int x, int y, int gridW, int gridH) {
        return isFree(x, y) && inLinkRange(x, y, Mathf.clamp(x, 0, gridW - 1), Mathf.clamp(y, 0, gridH - 1), 1);
    }

    private static void addProcessor(Seq<PendingTile> pending, Point pos, int linkX, int linkY, String code) {
        PendingTile pt = new PendingTile(Blocks.microProcessor, pos.x, pos.y, false);
        pt.code = code;
        pt.linkX = linkX;
        pt.linkY = linkY;
        pending.add(pt);
        markOccupied(pos.x, pos.y, 1, 1, true);
    }

    private static void creatDisplayGreed(int gridW, int gridH, int dBlockSize, Seq<PendingTile> pending) {
        // Позиция блока в схеме — его "центральный" тайл, а не левый нижний угол
        int displayOffset = -Main.coreDisplay.sizeOffset;

        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                // Ставим дисплеи с шагом их размера (без дырок)
                int dx = gx * dBlockSize;
                int dy = gy * dBlockSize;
                pending.add(new PendingTile(Main.coreDisplay, dx+displayOffset, dy+displayOffset, true));

                // Важно: помечаем ВСЮ площадь дисплея как занятую
                markOccupied(dx, dy, dBlockSize, dBlockSize, true);
            }
        }
    }


    // Поиск "слоями" вокруг блока с ограничениями.
    private static Point findNearestFree(int sx, int sy, int ds, boolean isCentral) {
        int maxR = isCentral ? centralDistLink : edgeDistLink;
        // Увеличиваем дистанцию r (перпендикулярно грани), но ограничиваем боковой сдвиг
        for (int r = 1; r <= maxR; r++) {

            // Для центральных блоков боковой разлет ограничен centralSideLimit.
            // Для крайних блоков разлет растет вместе с радиусом r.
            int sideLimit = isCentral ? centralSideLimit : r;


            // 1. Проверяем горизонтальные линии (снизу и сверху от дисплея)
            for (int x = sx - sideLimit; x < sx + ds + sideLimit; x++) {
                if (canPlace(x, sy - r, sx, sy, ds)) return new Point(x, sy - r);
                if (canPlace(x, sy + ds + r - 1, sx, sy, ds)) return new Point(x, sy + ds + r - 1);
            }

            // 2. Проверяем вертикальные линии (слева и справа от дисплея)
            for (int y = sy - sideLimit + 1; y < sy + ds + sideLimit - 1; y++) {
                if (canPlace(sx - r, y, sx, sy, ds)) return new Point(sx - r, y);
                if (canPlace(sx + ds + r - 1, y, sx, sy, ds)) return new Point(sx + ds + r - 1, y);
            }
        }
        return null; // Не нашли в радиусе линка
    }


    // Альтернативный метод: поиск СВОБОДНЫХ КЛЕТОК вплотную к дисплею
    private static Point findAdjacentFree(int sx, int sy, int ds) {
        // Проверяем 4 стороны вплотную (r=1)
        int[][] dirs = {
                {0, -1},  // Снизу
                {0, ds},  // Сверху
                {-1, 0},  // Слева
                {ds, 0}   // Справа
        };

        for (int[] d : dirs) {
            int px = sx + d[0];
            int py = sy + d[1];

            // Если это сторона, проверяем всю линию
            if (d[0] == 0) { // Вертикальная сторона (низ/верх)
                for (int x = sx; x < sx + ds; x++) {
                    if (isFree(x, py)) return new Point(x, py);
                }
            } else { // Горизонтальная сторона (лево/право)
                for (int y = sy; y < sy + ds; y++) {
                    if (isFree(px, y)) return new Point(px, y);
                }
            }
        }
        return null;
    }

    // sx, sy — левый нижний угол дисплея размером ds
    private static boolean canPlace(int x, int y, int sx, int sy, int ds) {
        return isFree(x, y) && inLinkRange(x, y, sx + (ds - 1) / 2f, sy + (ds - 1) / 2f, ds);
    }

    // Та же проверка, что в LogicBuild.validLink: иначе линк в игре молча отвалится
    private static boolean inLinkRange(int x, int y, float targetX, float targetY, int targetSize) {
        float range = ((LogicBlock) Blocks.microProcessor).range + targetSize * Vars.tilesize / 2f;
        return Mathf.within(x * Vars.tilesize, y * Vars.tilesize, targetX * Vars.tilesize, targetY * Vars.tilesize, range - 0.5f);
    }

    private static boolean isFree(int x, int y) {
        int ix = x + OFFSET, iy = y + OFFSET;
        if (ix < 0 || iy < 0 || ix >= MAP_SIZE || iy >= MAP_SIZE) return false;
        return !occupied[ix][iy];
    }

    private static void markOccupied(int x, int y, int w, int h, boolean val) {
        for (int ix = x; ix < x + w; ix++) {
            for (int iy = y; iy < y + h; iy++) {
                int ox = ix + OFFSET, oy = iy + OFFSET;
                if (ox >= 0 && oy >= 0 && ox < MAP_SIZE && oy < MAP_SIZE) {
                    occupied[ox][oy] = val;
                }
            }
        }
    }

    // --- Масштабирование и генерация кода ---
    private static void scaleAndDraw(Pixmap src, Pixmap dst, int fullW, int fullH) {
        int srcW = src.width, srcH = src.height;
        if (Main.coreScaling == 0) resample(src, 0, 0, srcW, srcH, dst, 0, 0, fullW, fullH);
        else if (Main.coreScaling == 1) {
            float sA = (float)fullW / fullH, iA = (float)srcW / srcH;
            int sx = 0, sy = 0, sw = srcW, sh = srcH;
            if (iA > sA) { sw = (int)(srcH * sA); sx = (srcW - sw) / 2; }
            else { sh = (int)(srcW / sA); sy = (srcH - sh) / 2; }
            resample(src, sx, sy, sw, sh, dst, 0, 0, fullW, fullH);
        } else {
            float ratio = Math.min((float)fullW / srcW, (float)fullH / srcH);
            int dw = (int)(srcW * ratio), dh = (int)(srcH * ratio);
            resample(src, 0, 0, srcW, srcH, dst, (fullW - dw) / 2, (fullH - dh) / 2, dw, dh);
        }
    }

    // Масштабирование с усреднением: каждый целевой пиксель — среднее всех исходных под ним.
    // Pixmap.draw берёт ближайший пиксель, и при сильном уменьшении фото идёт "рябью".
    // При увеличении это обычный nearest — пиксель-арт остаётся чётким.
    private static void resample(Pixmap src, int sx, int sy, int sw, int sh, Pixmap dst, int dx, int dy, int dw, int dh) {
        for (int i = 0; i < dw; i++) {
            int x0 = sx + (int)((long)i * sw / dw);
            int x1 = Math.max(x0 + 1, sx + (int)(((long)(i + 1) * sw + dw - 1) / dw));

            for (int j = 0; j < dh; j++) {
                int y0 = sy + (int)((long)j * sh / dh);
                int y1 = Math.max(y0 + 1, sy + (int)(((long)(j + 1) * sh + dh - 1) / dh));

                // Цвет копим домноженным на альфу, чтобы прозрачные пиксели не тянули его в свою сторону
                long r = 0, g = 0, b = 0, a = 0;
                for (int x = x0; x < x1; x++) {
                    for (int y = y0; y < y1; y++) {
                        int c = src.get(x, y);
                        int ca = c & 0xff;
                        r += ((c >>> 24) & 0xff) * ca;
                        g += ((c >>> 16) & 0xff) * ca;
                        b += ((c >>> 8) & 0xff) * ca;
                        a += ca;
                    }
                }

                int count = (x1 - x0) * (y1 - y0);
                int out = 0;
                if (a > 0) {
                    out = (int)(r / a) << 24 | (int)(g / a) << 16 | (int)(b / a) << 8 | (int)(a / count);
                }
                dst.set(dx + i, dy + j, out);
            }
        }
    }

    private static Seq<String> generateCode(ObjectMap<String, Seq<RectInt>> optimized, int height) {
        // Лимиты игры: 1000 инструкций в процессоре и 256 графических команд до drawflush
        // (всё, что сверх буфера, молча выбрасывается)
        int maxLines = Mathf.clamp(Main.coreSpeed, 10, LExecutor.maxInstructions);
        int maxCmds = Mathf.clamp(Main.separateSpeed, 2, LExecutor.maxGraphicsBuffer);

        Seq<String> codeBlocks = new Seq<>();
        Seq<String> current = new Seq<>();
        int lines = 0;   // Общее кол-во строк в текущем процессоре
        int cmds = 0;    // Кол-во графических команд (color + rect) с момента последнего flush
        String lastCol = null;

        for (var entry : optimized.entries()) {
            String colorCmd = entry.key;

            for (RectInt r : entry.value) {
                // 1. Проверка на переполнение процессора
                // Запас 5 строк на случай внезапного flush и установки цвета
                if (lines >= maxLines - 5) {
                    if (cmds > 0) {
                        current.add("drawflush display1");
                        lines++;
                    }
                    codeBlocks.add(current.toString("\n"));
                    current.clear();
                    lines = 0;
                    cmds = 0;
                    lastCol = null; // В новом процессоре цвет нужно поставить заново
                }

                // 2. Проверка графического буфера: цвет тоже занимает место
                boolean newColor = lastCol == null || !lastCol.equals(colorCmd);
                if (cmds + (newColor ? 2 : 1) > maxCmds) {
                    current.add("drawflush display1");
                    lines++;
                    cmds = 0;
                    // Между нашими flush другие процессоры могут сменить цвет дисплея
                    lastCol = null;
                    newColor = true;
                }

                // 3. Установка цвета (если сменился или после flush)
                if (newColor) {
                    current.add(colorCmd);
                    lines++;
                    cmds++;
                    lastCol = colorCmd;
                }

                // 4. Команда рисования
                current.add("draw rect " + r.x + " " + (height - r.y - r.height) + " " + r.width + " " + r.height);
                lines++;
                cmds++;
            }
        }

        // 5. Закрываем последний процессор, если в нём что-то есть
        if (current.size > 0) {
            if (cmds > 0) {
                current.add("drawflush display1");
            }
            codeBlocks.add(current.toString("\n"));
        }

        return codeBlocks;
    }

    static class Point { int x, y; Point(int x, int y) { this.x = x; this.y = y; } }
    static class PendingTile {
        Block block;
        int x, y, linkX, linkY;
        String code;
        boolean isDisplay;

        PendingTile(Block b, int x, int y, boolean isDisplay) {
            this.block = b; this.x = x; this.y = y; this.isDisplay = isDisplay;
        }
    }
    static class DisplayInfo {
        int gx, gy, priority;
        DisplayInfo(int gx, int gy, int p) { this.gx = gx; this.gy = gy; this.priority = p; }
    }

    static boolean isTiledDisplay() {
        return Main.coreDisplay instanceof TileableLogicDisplay;
    }

    public static void showCleanupDialog() {
        // 1. Собираем список схем, подходящих под критерии
        Seq<Schematic> toRemove = Vars.schematics.all().select(s ->
                s.name() != null &&
                        s.name().contains("_PickMe ") &&
                        s.labels.contains("_arts")
        );

        // Если ничего не нашли, просто уведомляем и выходим
        if (toRemove.isEmpty()) {
            Vars.ui.showInfoFade("No old PickMe arts found.");
            return;
        }

        // 2. Создаем диалог подтверждения
        BaseDialog dialog = new BaseDialog("Cleanup Old Arts");

        dialog.cont.add("Found [accent]" + toRemove.size + "[] old schematics:").padBottom(10).row();

        // Список названий в скролл-панели (на случай если их очень много)
        dialog.cont.pane(table -> {
            table.top().left();
            for (Schematic s : toRemove) {
                table.add("[lightgray]- " + s.name()).left().row();
            }
        }).size(400, 300).pad(10).row();

        dialog.cont.add("[scarlet]Are you sure you want to delete these files?[]").padTop(10);

        // 3. Кнопки управления
        dialog.buttons.defaults().size(200, 60).pad(10);

        dialog.buttons.button("Cancel", Icon.cancel, dialog::hide);

        dialog.buttons.button("Delete All", Icon.trash, () -> {
            // Удаляем каждую схему
            for (Schematic s : toRemove) {
                Vars.schematics.remove(s);
            }

            dialog.hide();
            Vars.ui.showInfoFade("Deleted " + toRemove.size + " schematics.");
            Log.info("PickMe: Manual cleanup finished.");
            Vars.ui.schematics.hide();

        }).color(Pal.remove);

        dialog.show();
    }
}
