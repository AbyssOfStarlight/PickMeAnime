package fallen;

import arc.Core;
import arc.graphics.*;
import arc.struct.*;
import arc.util.Log;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicBlock.*;

public class Exporter {
    static boolean[][] occupied;
    static final int MAP_SIZE = 800; // Размер виртуального поля
    static final int OFFSET = 300;   // Смещение, чтобы искать в "отрицательных" зонах
    static int centralDistLink = 10;
    static int edgeDistLink = 9;
    static int centralSideLimit = 1;



    public static void export(Pixmap input) {
        if (input == null) return;

        Log.info("Export started: quality=" + Main.coreQuality + ", size=" + input.width + "x" + input.height);
        int size = Main.coreSize;
        int gridW = Main.coreGridW;
        int gridH = Main.coreGridH;
        int dBlockSize = Main.coreDisplay.size;

        occupied = new boolean[MAP_SIZE][MAP_SIZE];
        Seq<PendingTile> pending = new Seq<>();

        int fullW = size * gridW;
        int fullH = size * gridH;

        Pixmap fullPixmap = new Pixmap(fullW, fullH);
        fullPixmap.fill(Color.rgba8888(0f, 0f, 0f, 1f));

        try {
            // 1. Изменяем размеры картинок
            scaleAndDraw(input, fullPixmap, fullW, fullH);

            // 2. Планируем сетку дисплеев
            creatDisplayGreed(gridW, gridH, dBlockSize, pending);

            //3. Создаём блоки схем
            if (isTiledDisplay()) {
                createTiledScheme(gridW, gridH, dBlockSize, pending, fullPixmap);
            } else {
                creatDisplaySheme(size, gridW, gridH, dBlockSize, pending, fullPixmap);
            }

            // 4. Нормализация координат (сдвиг всей пачки в 0,0)
            int minX = MAP_SIZE, minY = MAP_SIZE;
            for (PendingTile pt : pending) {
                minX = Math.min(minX, pt.x);
                minY = Math.min(minY, pt.y);
            }
            for (PendingTile pt : pending) {
                pt.x -= minX;
                pt.y -= minY;
            }

            // 5. Сборка и сохранение финальной схемы
            buildAndSaveSchemeTiles(gridW, gridH, pending);
        } catch (Exception e) {
            Log.err(e);
        } finally {
            fullPixmap.dispose();
        }
    }

    private static void buildAndSaveSchemeTiles(int gridW, int gridH, Seq<PendingTile> pending) {
        Seq<Schematic.Stile> tiles = new Seq<>();

        // Запоминаем финальные позиции дисплеев для линковки
        ObjectMap<String, Point> finalDispPos = new ObjectMap<>();
        for (PendingTile pt : pending) {
            if (pt.isDisplay) finalDispPos.put(pt.gx + "," + pt.gy, new Point(pt.x, pt.y));
        }

        for (PendingTile pt : pending) {
            if (pt.isDisplay) {
                tiles.add(new Schematic.Stile(pt.block, pt.x, pt.y, null, (byte)0));
            } else {
                LogicBuild build = (LogicBuild) Blocks.microProcessor.newBuilding();
                build.tile = new Tile(pt.x, pt.y, Blocks.stone, Blocks.air, Blocks.microProcessor);
                build.team = Team.sharded;

                // Линкуем к правильной позиции дисплея
                Point dp = finalDispPos.get(pt.gx + "," + pt.gy);
                build.links.add(new LogicLink(dp.x, dp.y, "display1", true));
                build.updateCode(pt.code);

                tiles.add(new Schematic.Stile(pt.block, pt.x, pt.y, build.config(), (byte)0));
            }
        }

        // 5. Сохранение
        int mw = 0, mh = 0;
        for (var s : tiles) {
            mw = Math.max(mw, s.x + s.block.size);
            mh = Math.max(mh, s.y + s.block.size);
        }

        StringMap tags = new StringMap();
        tags.put("name", "_PickMe " + gridW + "x" + gridH);
        tags.put("description", "Where hentai?");
        tags.put("labels", "[\"_arts\"]");
        Schematic schem = new Schematic(tiles, tags, mw, mh);
        schem.labels.add("_arts");

        if(Main.saveGenerate) Vars.schematics.add(schem);
        Vars.ui.schematics.hide();
        Vars.control.input.useSchematic(schem);
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
        Seq<Integer> globalRgb = new Seq<>();
        Seq<float[]> globalHsv = new Seq<>();
        if (Main.coreQuality < 255) {
            Processor.buildGlobalPalette(fullPixmap, globalRgb, globalHsv);
        }
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
            if (Main.coreQuality < 255) {
                Processor.applyPalette(region, globalRgb, globalHsv);
            }
            // Только группируем в прямоугольники (без пересчёта палитры)
            ObjectMap<String, Seq<RectInt>> optimized = Processor.groupOnly(region);
            region.dispose();

            for (String code : generateCode(optimized, size)) {
                // Ищем место вплотную к дисплею
                Point pos = findAdjacentFree(dispX, dispY, dBlockSize);
                if (pos == null) {
                    // Если вплотную нет, ищем в радиусе 8 тайлов
                    pos = findNearestFree(dispX, dispY, dBlockSize, isCentral);
                }

                if (pos != null) {
                    PendingTile pt = new PendingTile(Blocks.microProcessor, pos.x, pos.y, false);
                    pt.code = code;
                    pt.gx = gx; pt.gy = gy;
                    pending.add(pt);
                    markOccupied(pos.x, pos.y, 1, 1, true);
                } else {
                    Log.warn("Не удалось разместить процессор для дисплея " + gx + "," + gy);
                }
            }
        }
    }

    private static void createTiledScheme(int gridW, int gridH, int dBlockSize, Seq<PendingTile> pending, Pixmap fullPixmap) {
        // Размер локального канваса ОДНОГО дисплея в пикселях (у каждого дисплея свои
        // координаты 0..size, поэтому картинку нужно резать по дисплеям, а не брать целиком)
        int size = fullPixmap.width / gridW;

        // 1. Строим и применяем ОДНУ глобальную палитру на всё изображение
        //    (чтобы цвета на стыках соседних дисплеев совпадали)
        Seq<Integer> globalRgb = new Seq<>();
        Seq<float[]> globalHsv = new Seq<>();
        if (Main.coreQuality < 255) {
            Processor.buildGlobalPalette(fullPixmap, globalRgb, globalHsv);
            Processor.applyPalette(fullPixmap, globalRgb, globalHsv);
        }

        int totalW = gridW * dBlockSize;
        int totalH = gridH * dBlockSize;

        // 2. Обрабатываем каждый дисплей отдельно, в его ЛОКАЛЬНЫХ координатах
        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                int dispX = gx * dBlockSize;
                int dispY = gy * dBlockSize;

                Pixmap region = new Pixmap(size, size);
                region.draw(fullPixmap, gx * size, (gridH - 1 - gy) * size, size, size, 0, 0, size, size);

                ObjectMap<String, Seq<RectInt>> optimized = Processor.groupOnly(region);
                region.dispose();

                for (String code : generateCode(optimized, size)) {
                    // Ищем место кольцами вокруг КОНКРЕТНОГО дисплея.
                    // Радиус большой (до 50), т.к. tile-дисплеи стоят вплотную друг к другу
                    // без зазоров, и свободное место может найтись только на внешней границе стены.
                    Point pos = null;
                    search:
                    for (int r = 1; r < 50; r++) {
                        for (int x = dispX - r; x < dispX + dBlockSize + r; x++) {
                            if (isFree(x, dispY - r)) { pos = new Point(x, dispY - r); break search; }
                            if (isFree(x, dispY + dBlockSize + r - 1)) { pos = new Point(x, dispY + dBlockSize + r - 1); break search; }
                        }
                        for (int y = dispY - r + 1; y < dispY + dBlockSize + r - 1; y++) {
                            if (isFree(dispX - r, y)) { pos = new Point(dispX - r, y); break search; }
                            if (isFree(dispX + dBlockSize + r - 1, y)) { pos = new Point(dispX + dBlockSize + r - 1, y); break search; }
                        }
                    }

                    if (pos != null) {
                        PendingTile pt = new PendingTile(Blocks.microProcessor, pos.x, pos.y, false);
                        pt.code = code;
                        pt.gx = gx; pt.gy = gy;
                        pending.add(pt);
                        markOccupied(pos.x, pos.y, 1, 1, true);
                    } else {
                        Log.warn("Не удалось разместить процессор для tile-дисплея " + gx + "," + gy);
                    }
                }
            }
        }
    }

    private static void creatDisplayGreed(int gridW, int gridH, int dBlockSize, Seq<PendingTile> pending) {
        int displayOffset = Main.coreDOffset;
        if(Main.coreDisplay.name.contains("large")){
            displayOffset = Main.coreDOffset + 2;
        } else
        if(Main.coreDisplay.name.contains("tile")){
            displayOffset = Main.coreDOffset + 0;
        } else {
            displayOffset = Main.coreDOffset + 1;
        }

        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                // Ставим дисплеи с шагом их размера (без дырок)
                int dx = gx * dBlockSize;
                int dy = gy * dBlockSize;
                PendingTile pt = new PendingTile(Main.coreDisplay, dx+displayOffset, dy+displayOffset, true);
                pt.gx = gx; pt.gy = gy;
                pending.add(pt);

                // Важно: помечаем ВСЮ площадь дисплея как занятую
                markOccupied(dx, dy, dBlockSize, dBlockSize, true);
            }
        }
    }


    // Поиск "слоями" вокруг блока с ограничениями.
    private static Point findNearestFree(int sx, int sy, int ds, boolean isCentral) {
        int maxR = isCentral ? centralDistLink : edgeDistLink;
        // Увеличиваем дистанцию r (перпендикулярно грани), но ограничиваем боковой сдвиг
        // 10 - это разумный предел дальности линка для микропроцессора
        for (int r = 1; r <= maxR; r++) {

            // Для центральных блоков боковой разлет строго 6.
            // Для крайних блоков разлет растет вместе с радиусом r.
            int sideLimit = isCentral ? centralSideLimit : r;


            // 1. Проверяем горизонтальные линии (снизу и сверху от дисплея)
            for (int x = sx - sideLimit; x < sx + ds + sideLimit; x++) {
                if (isFree(x, sy - r)) return new Point(x, sy - r);
                if (isFree(x, sy + ds + r - 1)) return new Point(x, sy + ds + r - 1);
            }

            // 2. Проверяем вертикальные линии (слева и справа от дисплея)
            for (int y = sy - sideLimit + 1; y < sy + ds + sideLimit - 1; y++) {
                if (isFree(sx - r, y)) return new Point(sx - r, y);
                if (isFree(sx + ds + r - 1, y)) return new Point(sx + ds + r - 1, y);
            }
        }
        return null; // Не нашли в безопасном радиусе
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
        if (Main.coreScaling == 0) dst.draw(src, 0, 0, srcW, srcH, 0, 0, fullW, fullH);
        else if (Main.coreScaling == 1) {
            float sA = (float)fullW / fullH, iA = (float)srcW / srcH;
            int sx = 0, sy = 0, sw = srcW, sh = srcH;
            if (iA > sA) { sw = (int)(srcH * sA); sx = (srcW - sw) / 2; }
            else { sh = (int)(srcW / sA); sy = (srcH - sh) / 2; }
            dst.draw(src, sx, sy, sw, sh, 0, 0, fullW, fullH);
        } else {
            float ratio = Math.min((float)fullW / srcW, (float)fullH / srcH);
            int dw = (int)(srcW * ratio), dh = (int)(srcH * ratio);
            dst.draw(src, 0, 0, srcW, srcH, (fullW - dw) / 2, (fullH - dh) / 2, dw, dh);
        }
    }

    private static Seq<String> generateCode(ObjectMap<String, Seq<RectInt>> optimized, int size) {
        Seq<String> codeBlocks = new Seq<>();
        Seq<String> current = new Seq<>();
        int lines = 0;   // Общее кол-во строк в текущем процессоре
        int draws = 0;   // Кол-во команд draw с момента последнего flush
        String lastCol = null;

        for (var entry : optimized.entries()) {
            String colorCmd = entry.key;

            for (RectInt r : entry.value) {
                // 1. Проверка на переполнение процессора (Main.coreSpeed)
                // Запас 5 строк на случай внезапного flush и установки цвета
                if (lines >= Main.coreSpeed - 5) {
                    if (draws > 0) {
                        current.add("drawflush display1");
                        lines++;
                    }
                    codeBlocks.add(current.toString("\n"));
                    current.clear();
                    lines = 0;
                    draws = 0;
                    lastCol = null; // В новом процессоре цвет нужно поставить заново
                }

                // 2. Установка цвета (если сменился или процессор новый)
                if (lastCol == null || !lastCol.equals(colorCmd)) {
                    current.add(colorCmd);
                    lines++;
                    lastCol = colorCmd;
                }

                // 3. Команда рисования
                current.add("draw rect " + r.x + " " + (size - r.y - r.height) + " " + r.width + " " + r.height);
                lines++;
                draws++;

                // 4. Проверка графического буфера (Mindustry limit = 256)
                // Используем 250 для безопасности.
                if (draws >= Main.separateSpeed) {
                    current.add("drawflush display1");
                    lines++;
                    draws = 0;
                    lastCol = null; // Цвет сбрасывается после флоша!
                }
            }
        }

        // 5. Закрываем последний процессор, если в нём что-то есть
        if (current.size > 0) {
            // Если были команды рисования, но не было флоша в конце
            if (draws > 0) {
                current.add("drawflush display1");
            }
            codeBlocks.add(current.toString("\n"));
        }

        //Log.info("Generated " + codeBlocks.size + " code blocks");
        return codeBlocks;
    }

    static class Point { int x, y; Point(int x, int y) { this.x = x; this.y = y; } }
    static class PendingTile {
        Block block;
        int x, y, gx, gy;
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
        return Main.coreDisplay.name.contains("tile");
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