package fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.Draw;
import arc.scene.ui.TextField;
import arc.util.*;
import mindustry.*;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.ui.FileChooser;
import mindustry.ui.dialogs.*;
import mindustry.game.Schematic;
import mindustry.world.Block;
import mindustry.world.blocks.logic.*;
import arc.graphics.Pixmap;

import static fallen.Exporter.*;

public class Main extends Mod {
    public static LogicDisplay coreDisplay;
    public static int coreSize = 80;
    public static int coreSpeed = 996;
    public static int separateSpeed = 100;
    public static int coreQuality = 255;
    public static boolean coreHsv = false;
    public static boolean coreUseGray = false;
    public static boolean saveGenerate = Core.settings.getBool("pma-savegen", true);
    public static int coreScaling = 0;
    public static Pixmap currentImage = null;
    public static int coreGridW = 1;
    public static int coreGridH = 1;
    public static boolean drawDisplayBorders = Core.settings.getBool("pm-ddb", false);
    static boolean exporting = false;

    @Override
    public void init() {
        coreDisplay = (LogicDisplay) Blocks.largeLogicDisplay;
        coreSize = coreDisplay.displaySize;

        Events.on(EventType.ClientLoadEvent.class, e -> {
            Vars.ui.schematics.buttons.button("PickMe", Icon.paste, this::showMainDialog);
        });
        testOverrideDisplay();
    }

    void showMainDialog() {
        BaseDialog ptl = new BaseDialog("CreateScheme");

        ptl.cont.pane(t -> {
            t.defaults().pad(4).growX();

            // --- СЕКЦИЯ 1: ВЫБОР ИЗОБРАЖЕНИЯ ---
            t.table(Tex.buttonTrans, img -> {
                img.add("[coral]1. Select Image:[]").row();

                // Обернем кнопки в горизонтальную таблицу
                img.table(buttons -> {
                    // Основная кнопка выбора файла
                    buttons.button(currentImage == null ? "Choose File..." : "Change Image", Icon.file, () -> {
                        // Используем новый API через FileChooser
                        FileChooser.open("png", "jpg", "jpeg", "bmp") // Только то, что умеет stb_image (webp — нет)
                                .title("Select Image") // Заголовок окна
                                .submit(file -> {      // Что делать с результатом (Fi file)
                                    try {
                                        // Создаем Pixmap из выбранного файла
                                        Pixmap loaded = new Pixmap(file);

                                        if (currentImage != null) currentImage.dispose();
                                        currentImage = loaded;

                                        // Перерисовываем окно
                                        ptl.hide();
                                        showMainDialog();
                                    } catch (Exception ex) {
                                        Vars.ui.showException("Failed to load image", ex);
                                    }
                                });
                    }).size(230, 50).disabled(b -> exporting);

                    // Кнопка вставки из буфера (только для ПК; на macOS AWT рядом с окном игры может зависнуть)
                    if (!Vars.mobile && !OS.isMac) {
                        buttons.button(Icon.copy, () -> {
                            try {
                                Pixmap pix = ClipboardImport.read();

                                if (pix != null) {
                                    if (currentImage != null) currentImage.dispose();
                                    currentImage = pix;

                                    ptl.hide();
                                    showMainDialog();
                                    //Vars.ui.showInfo("Image pasted from clipboard!");
                                } else {
                                    Vars.ui.showInfo("Clipboard doesn't contain an image.");
                                }
                            } catch (Throwable ex) {
                                Vars.ui.showException("Failed to paste image", ex);
                            }
                        }).size(50, 50).padLeft(4).tooltip("Paste from clipboard").disabled(b -> exporting);
                    }
                }).row();
                img.table(info -> {
                    info.defaults().center();


                    info.label(() -> {
                        if (currentImage == null) return "[gray]No image loaded.[]";

                        int w = currentImage.width;
                        int h = currentImage.height;
                        float imgRatio = (float)w / h;

                        String ratioText = Strings.fixed(imgRatio, 2) + ":1";
                        String orientation = (w == h) ? "Square" : (imgRatio > 1 ? "Wide" : "Tall");

                        // --- ЛОГИКА ПОДБОРА ДИСПЛЕЕВ ---
                        int bestW = 1, bestH = 1;
                        float minDiff = Float.MAX_VALUE;

                        // Перебираем все возможные сетки от 1 до 16
                        for(int tw = 1; tw <= 16; tw++){
                            for(int th = 1; th <= 16; th++){
                                float displayRatio = (float)tw / th;
                                float diff = Math.abs(displayRatio - imgRatio);

                                if(diff < minDiff){
                                    minDiff = diff;
                                    bestW = tw;
                                    bestH = th;
                                }
                            }
                        }

                        String distortionWarning = (minDiff / imgRatio > 0.1f) ? " [scarlet](distorted)[]" : "";

                        return "Size: [white]" + w + "x" + h + "[]\n" +
                                "Ratio: [white]" + ratioText + "[] (" + orientation + ")\n" +
                                "Best Tile: [accent]" + bestW + "x" + bestH + "[] displays" + distortionWarning;

                    }).padTop(8f).center();

                }).growX().center();
            }).row();

            // --- СЕКЦИЯ 2: РЕЖИМ МАСШТАБИРОВАНИЯ ---
            t.table(Tex.buttonTrans, sc -> {
                sc.add("[coral]2. Scaling Mode:[]").left().row();
                sc.label(() -> {
                    if(coreScaling == 0) return "Stretch (Distort)";
                    if(coreScaling == 1) return "Crop (Fill Display)";
                    return "Letterbox (Fit Entirely)";
                }).color(Color.lightGray).row();
                sc.slider(0, 2, 1, coreScaling, n -> coreScaling = (int)n).width(280);
            }).row();

            // --- СЕКЦИЯ 3: КАЧЕСТВО И ТЕХ. НАСТРОЙКИ ---
            t.table(Tex.buttonTrans, tech -> {
                tech.add("[coral]3. Quality & Speed:[]").left().row();

                tech.table(q -> {
                    q.add("Quality: ").left();
                    q.label(() -> String.valueOf(coreQuality)).color(Color.gray);
                }).row();
                tech.slider(0, 255, 1, coreQuality, n -> coreQuality = (int)n).width(280).row();

                tech.table(s -> {
                    s.add("Proc. Lines: ").left();
                    s.field(String.valueOf(coreSpeed), str -> coreSpeed = Strings.parseInt(str, 996))
                            .width(100).get().setFilter(TextField.TextFieldFilter.digitsOnly);
                }).row();

                tech.table(s -> {
                    s.add("Separate. Lines: ").left();
                    s.field(String.valueOf(separateSpeed), str -> separateSpeed = Strings.parseInt(str, 100))
                            .width(100).get().setFilter(TextField.TextFieldFilter.digitsOnly);
                }).row();

                tech.check("Gray Transparency", coreUseGray, b -> coreUseGray = b).left().row();
                tech.check("Use HSV Indexing", coreHsv, b -> coreHsv = b)
                        .disabled(b -> coreQuality == 255).left().row();
            }).row();

            // --- СЕКЦИЯ 4: ВЫБОР ДИСПЛЕЯ ---
            t.table(Tex.buttonTrans, grid -> {
                grid.add("[coral]4. Grid Size (Width x Height):[]").left().row();
                if(Exporter.isTiledDisplay()){
                    grid.table(gt -> {
                        gt.label(() -> coreGridW + " x " + coreGridH).color(Color.gray).padRight(10).row();
                        gt.add("W: ");
                        gt.slider(1, 16, 1, coreGridW, n -> coreGridW = (int)n).width(300).row();
                        gt.add(" H: ").padLeft(10);
                        gt.slider(1, 16, 1, coreGridH, n -> coreGridH = (int)n).width(300);
                    }).row();
                } else{
                    grid.table(gt -> {
                        gt.label(() -> coreGridW + " x " + coreGridH).color(Color.gray).padRight(10).row();
                        gt.add("W: ");
                        gt.slider(1, 10, 1, coreGridW, n -> coreGridW = (int)n).width(300).row();
                        gt.add(" H: ").padLeft(10);
                        gt.slider(1, 10, 1, coreGridH, n -> coreGridH = (int)n).width(300);
                    }).row();
                }
            }).row();
            t.table(Tex.buttonTrans, disp -> {
                disp.add("[coral]5. Target Display:[]").left().row();
                disp.button(coreDisplay.localizedName, () -> {
                    BaseDialog sel = new BaseDialog("Select Display");
                    Vars.content.blocks().each(b -> b instanceof LogicDisplay, b -> {
                        sel.cont.button(b.localizedName, () -> {
                            coreDisplay = (LogicDisplay) b;
                            coreSize = coreDisplay.displaySize;
                            // У обычных дисплеев слайдер сетки до 10, у tile — до 16
                            int maxGrid = Exporter.isTiledDisplay() ? 16 : 10;
                            coreGridW = Math.min(coreGridW, maxGrid);
                            coreGridH = Math.min(coreGridH, maxGrid);
                            sel.hide();
                            ptl.hide(); showMainDialog();
                        }).size(250, 60).row();
                    });
                    sel.addCloseButton();
                    sel.show();
                }).size(280, 60);
            }).row();

            // --- СЕКЦИЯ 5: Настройки генерации ---
            t.table(Tex.buttonTrans, sc -> {
                sc.add("[coral]6. Generation Settings:[]").left().row();
                sc.label(() -> "centralDistLink: " + centralDistLink).color(Color.lightGray).row();
                sc.slider(0, 15, 1, centralDistLink, n -> centralDistLink = (int)n).width(600f).row();

                sc.label(() -> "edgeDistLink: " + edgeDistLink).color(Color.lightGray).row();
                sc.slider(0, 15, 1, edgeDistLink, n -> edgeDistLink = (int)n).width(600f).row();

                sc.label(() -> "centralSideLimit: " + centralSideLimit).color(Color.lightGray).row();
                sc.slider(0, 15, 1, centralSideLimit, n -> centralSideLimit = (int)n).width(600f).row();

                sc.check("Save Schematic after generate", saveGenerate, b -> {saveGenerate = b; Core.settings.put("pma-savegen", b);}).left().row();

            }).row();

            // --- СЕКЦИЯ 6: Что-то полезное ---

            t.table(Tex.buttonTrans, sc -> {
                sc.table(st ->{
                    st.add("[coral]7. Some Things:[]").left().row();
                    st.button("Delete all generated schemes", Exporter::showCleanupDialog).width(300f).row();

                    st.check("Draw Display Borders (off = borderless, affects ALL displays)", drawDisplayBorders, b -> {drawDisplayBorders = b; Core.settings.put("pm-ddb", b);}).left().row();

                });
            }).growX().row();

        }).grow();

        ptl.addCloseButton();
        ptl.buttons.button("EXPORT", Icon.export, () -> {
            exporting = true;
            Pixmap image = currentImage;
            Threads.daemon("PickMe worker", () -> {
                try {
                    Schematic schem = Exporter.export(image);
                    // Схемы, UI и GL — только из главного потока
                    Core.app.post(() -> {
                        exporting = false;
                        ptl.hide();
                        Exporter.useSchematic(schem);
                    });
                } catch (Throwable ex) {
                    Core.app.post(() -> {
                        exporting = false;
                        Vars.ui.showException(ex);
                    });
                }
            });
        }).size(180, 60).disabled(b -> currentImage == null || exporting);

        ptl.show();
    }

    private void testOverrideDisplay() {
        // Список блоков, которые мы хотим изменить (обычный и большой дисплеи)
        Block[] displayBlocks = {Blocks.logicDisplay, Blocks.largeLogicDisplay};

        for(Block b : displayBlocks){
            // Сохраняем ссылку на текущий блок, чтобы использовать её внутри анонимного класса
            LogicDisplay block = (LogicDisplay)b;

            block.buildType = () -> block.new LogicDisplayBuild() {
                @Override
                public void draw() {
                    // Рамки включены (по умолчанию) или дисплеи выключены в настройках игры —
                    // ведём себя как ванильный дисплей
                    if (drawDisplayBorders || !Vars.renderer.drawDisplays) {
                        super.draw();
                        return;
                    }

                    // Безрамочный режим: без спрайта блока, содержимое растянуто на весь блок
                    Draw.draw(Draw.z(), this::ensureBuffer);
                    processCommands();

                    Draw.blend(Blending.disabled);
                    Draw.draw(Draw.z(), () -> {
                        if (buffer != null) {
                            Draw.rect(Draw.wrap(buffer.getTexture()), x, y, block.size * Vars.tilesize, -block.size * Vars.tilesize);
                        }
                    });
                    Draw.blend();
                }
            };
        }
    }
}