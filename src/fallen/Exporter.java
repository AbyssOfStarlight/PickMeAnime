package fallen;

import arc.Core;
import arc.graphics.*;
import arc.struct.*;
import arc.util.Log;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.LogicBlock.*;

public class Exporter {

    public static void export(Pixmap input) {
        if (input == null) return;
        int size = Main.coreSize;
        Pixmap pixmap = new Pixmap(size, size);
        try {
            // 1. Масштабирование (если нужно)
            pixmap.fill(Color.rgba8888(0f, 0f, 0f, 1f));     // Заполняем фон черным для режима полос
            int srcW = input.width;
            int srcH = input.height;
            if (Main.coreScaling == 0) {
                pixmap.draw(input, 0, 0, srcW, srcH, 0, 0, size, size);

            } else if (Main.coreScaling == 1) {
                int srcX = 0, srcY = 0, srcPartSize;

                if (srcW > srcH) { // Пейзаж
                    srcPartSize = srcH;
                    srcX = (srcW - srcH) / 2;
                } else { // Портрет
                    srcPartSize = srcW;
                    srcY = (srcH - srcW) / 2;
                }
                pixmap.draw(input, srcX, srcY, srcPartSize, srcPartSize, 0, 0, size, size);

            } else {
                float ratio = Math.min((float)size / srcW, (float)size / srcH);
                int drawW = (int)(srcW * ratio);
                int drawH = (int)(srcH * ratio);
                int drawX = (size - drawW) / 2;
                int drawY = (size - drawH) / 2;

                pixmap.draw(input, 0, 0, srcW, srcH, drawX, drawY, drawW, drawH);
            }

            // 2. Группировка прямоугольников
            ObjectMap<String, Seq<RectInt>> optimized = Processor.process(pixmap);

            // 3. Генерация кода
            Seq<String> codeBlocks = new Seq<>();
            Seq<String> currentBlock = new Seq<>();

            int blockLines = 0;      // Строк в текущем процессоре
            int drawCalls = 0;       // Команд рисования с последнего flush
            String lastColor = null; // Текущий установленный цвет

            final int MAX_PROC_LINES = 990;   // Лимит строк на процессор
            final int FLUSH_INTERVAL = 54;   // Flush строго каждые 108 draw rect

            for (var entry : optimized.entries()) {
                String colorCmd = entry.key;

                for (RectInt rect : entry.value) {
                    // 1. Если процессор переполнен -> закрываем его и начинаем новый
                    if (blockLines >= MAX_PROC_LINES - 3) { // -3 запас на flush + цвет + rect
                        if (drawCalls > 0) {
                            currentBlock.add("drawflush display1");
                            blockLines++;
                        }
                        codeBlocks.add(currentBlock.toString("\n"));
                        currentBlock = new Seq<>();
                        blockLines = 0;
                        drawCalls = 0;
                        lastColor = null; // В новом процессоре цвет нужно задать заново
                    }

                    // 2. Установка цвета (обязательно после flush или в начале нового блока)
                    if (lastColor == null || !lastColor.equals(colorCmd)) {
                        currentBlock.add(colorCmd);
                        blockLines++;
                        lastColor = colorCmd;
                    }

                    // 3. Добавляем команду рисования
                    currentBlock.add("draw rect " + rect.x + " " + (size - rect.y - rect.height) +
                            " " + rect.width + " " + rect.height);
                    blockLines++;
                    drawCalls++;

                    // 4. ОБЯЗАТЕЛЬНЫЙ flush каждые 110 команд рисования
                    if (drawCalls >= FLUSH_INTERVAL) {
                        currentBlock.add("drawflush display1");
                        blockLines++;
                        drawCalls = 0;
                        lastColor = null; // Mindustry сбрасывает цвет после drawflush!
                    }
                }
            }

            // 5. Финализируем последний процессор
            if (currentBlock.size > 0) {
                if (drawCalls > 0) {
                    currentBlock.add("drawflush display1");
                }
                codeBlocks.add(currentBlock.toString("\n"));
            }


            // 4. Построение схемы
            int dispSize = Main.coreDisplay.size;
            // Вычисляем размер сетки так, чтобы влезли все процессоры вокруг дисплея
            int minDim = (int) Math.ceil(Math.sqrt(codeBlocks.size + (dispSize * dispSize)));
            int dim = Math.max(minDim, dispSize);

            int offset = dim - dispSize;
            int dispMin = offset / 2;
            int dispMax = dispMin + dispSize - 1;

            Seq<Schematic.Stile> tiles = new Seq<>();

            // Координата для центра дисплея (в тайлах)
            int dispCoord = dispMax - (dispSize / 2);
            tiles.add(new Schematic.Stile(Main.coreDisplay, dispCoord, dispCoord, null, (byte)0));

            // Координаты для процессоров (заполняем всё, кроме области дисплея)
            int i = 0;
            int finalWidth = 0, finalHeight = 0;

            outer:
            for (int x = 0; x < dim; x++) {
                for (int y = 0; y < dim; y++) {
                    if (i >= codeBlocks.size) break outer;

                    // Проверка: НЕ находится ли эта клетка внутри дисплея
                    if (!(x >= dispMin && x <= dispMax && y >= dispMin && y <= dispMax)) {

                        LogicBuild build = (LogicBuild) Blocks.microProcessor.newBuilding();
                        build.tile = new Tile(x, y, Blocks.stone, Blocks.air, Blocks.microProcessor);
                        build.team = Team.sharded;

                        // Линк строго на координаты дисплея
                        build.links.add(new LogicLink(dispCoord, dispCoord, "display1", true));
                        build.updateCode(codeBlocks.get(i));

                        tiles.add(new Schematic.Stile(Blocks.microProcessor, x, y, build.config(), (byte)0));
                        build.remove();//dont work, help me, plzzz

                        finalWidth = Math.max(finalWidth, x);
                        finalHeight = Math.max(finalHeight, y);
                        i++;
                    }
                }
            }

            // 5. Сохранение
            Schematic schem = new Schematic(tiles, new StringMap(){{
                put("name", "_PickMe " + minDim + "x" + dim);
            }}, finalWidth + 1, finalHeight + 1);

            Vars.schematics.add(schem);

            Core.app.post(() -> {
                Vars.ui.schematics.hide();
                Vars.control.input.useSchematic(schem);
            });

        } catch (Exception e) {
            Log.err("Export error", e);
        throw e;
        } finally {
            if (pixmap != null) pixmap.dispose();
        }
    }

    // Вспомогательный метод для линейного ресайза
    public static Pixmap scaleLinear(Pixmap src, int newW, int newH) {
        Pixmap dst = new Pixmap(newW, newH);

        float xRatio = (float)src.width / newW;
        float yRatio = (float)src.height / newH;

        for (int dy = 0; dy < newH; dy++) {
            for (int dx = 0; dx < newW; dx++) {
                // Координаты в исходном изображении
                float sx = dx * xRatio;
                float sy = dy * yRatio;

                // 4 соседних пикселя для интерполяции
                int x0 = Math.max(0, Math.min((int)Math.floor(sx), src.width - 2));
                int x1 = Math.max(0, Math.min(x0 + 1, src.width - 1));
                int y0 = Math.max(0, Math.min((int)Math.floor(sy), src.height - 2));
                int y1 = Math.max(0, Math.min(y0 + 1, src.height - 1));

                float tx = sx - x0, ty = sy - y0;

                Color c00 = new Color(src.get(x0, y0));
                Color c01 = new Color(src.get(x0, y1));
                Color c10 = new Color(src.get(x1, y0));
                Color c11 = new Color(src.get(x1, y1));

                // Билинейная интерполяция
                Color result = new Color();
                result.r = c00.r * (1-tx)*(1-ty) + c10.r * tx*(1-ty) +
                        c01.r * (1-tx)*ty + c11.r * tx*ty;
                result.g = c00.g * (1-tx)*(1-ty) + c10.g * tx*(1-ty) +
                        c01.g * (1-tx)*ty + c11.g * tx*ty;
                result.b = c00.b * (1-tx)*(1-ty) + c10.b * tx*(1-ty) +
                        c01.b * (1-tx)*ty + c11.b * tx*ty;
                result.a = 1f;

                dst.set(dx, dy, result.rgba8888());
            }
        }
        return dst;
    }
}