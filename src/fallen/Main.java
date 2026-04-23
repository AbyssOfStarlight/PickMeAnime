package fallen;

import arc.*;
import arc.graphics.*;
import arc.scene.ui.TextField;
import arc.util.*;
import mindustry.*;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.gen.*;
import mindustry.mod.*;
import mindustry.ui.dialogs.*;
import mindustry.world.blocks.logic.*;

public class Main extends Mod {
    public static LogicDisplay coreDisplay;
    public static int coreSize = 80;
    public static int coreSpeed = 1000;
    public static int coreQuality = 255;
    public static boolean coreHsv = false;
    public static boolean coreUseGray = false;
    public static int coreScaling = 0;
    public static Pixmap currentImage = null;

    @Override
    public void init() {
        coreDisplay = (LogicDisplay) Blocks.largeLogicDisplay;
        coreSize = coreDisplay.displaySize;

        Events.on(EventType.ClientLoadEvent.class, e -> {
            Vars.ui.schematics.buttons.button("Create Anime", Icon.paste, this::showMainDialog);
        });
    }

    void showMainDialog() {
        BaseDialog ptl = new BaseDialog("CreateAnime");

        ptl.cont.pane(t -> {
            t.defaults().pad(4).growX();

            // --- СЕКЦИЯ 1: ВЫБОР ИЗОБРАЖЕНИЯ ---
            t.table(Tex.buttonTrans, img -> {
                img.add("[coral]1. Select Image:[]").left().row();
                img.button(currentImage == null ? "Choose File..." : "Change Image", Icon.file, () -> {
                    Vars.platform.showFileChooser(true, "*", file -> {
                        try {
                            String ext = file.extension().toLowerCase();
                            if (!(ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("bmp"))) {
                                Vars.ui.showInfo("Invalid format!"); return;
                            }
                            if (currentImage != null) currentImage.dispose();
                            currentImage = new Pixmap(file);
                            ptl.hide(); showMainDialog(); // Переоткрываем для обновления текста кнопок
                        } catch (Exception ex) { Vars.ui.showException(ex); }
                    });
                }).size(280, 50);
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
                    s.field(String.valueOf(coreSpeed), str -> coreSpeed = Strings.parseInt(str, 1000))
                            .width(100).get().setFilter(TextField.TextFieldFilter.digitsOnly);
                }).row();

                tech.check("Gray Transparency", coreUseGray, b -> coreUseGray = b).left().row();
                tech.check("Use HSV Indexing", coreHsv, b -> coreHsv = b)
                        .disabled(b -> coreQuality == 255).left().row();
            }).row();

            // --- СЕКЦИЯ 4: ВЫБОР ДИСПЛЕЯ ---
            t.table(Tex.buttonTrans, disp -> {
                disp.add("[coral]4. Target Display:[]").left().row();
                disp.button(coreDisplay.localizedName, () -> {
                    BaseDialog sel = new BaseDialog("Select Display");
                    Vars.content.blocks().each(b -> b instanceof LogicDisplay, b -> {
                        sel.cont.button(b.localizedName, () -> {
                            coreDisplay = (LogicDisplay) b;
                            coreSize = coreDisplay.displaySize;
                            sel.hide();
                            ptl.hide(); showMainDialog();
                        }).size(250, 60).row();
                    });
                    sel.addCloseButton();
                    sel.show();
                }).size(280, 60);
            }).row();
        }).grow();

        ptl.addCloseButton();
        ptl.buttons.button("EXPORT", Icon.export, () -> {
            Threads.daemon("PickMe worker", () -> {
                try {
                    Exporter.export(currentImage);
                    Core.app.post(ptl::hide);
                } catch (Exception ex) {
                    Core.app.post(() -> { Vars.ui.showException(ex);});
                }
            });
        }).size(180, 60).disabled(b -> currentImage == null);

        ptl.show();
    }
}