package fallen;

import arc.*;
import arc.graphics.*;
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
        ptl.cont.add("[coral]1.[] Select a PNG image.").row();
        ptl.cont.add("[coral]2.[] Click [stat]Export[] to create a schematic.").row();

        ptl.cont.button("Select Image", () -> {
            Vars.platform.showFileChooser(true, "*", file -> {
                String ext = file.extension().toLowerCase();
                if (!(ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("bmp"))) {
                    Vars.ui.showInfo("Please select a valid image (png, jpg, bmp)");
                    return;
                }
                try {
                    if (currentImage != null) {
                        currentImage.dispose();
                    }
                    currentImage = new Pixmap(file);
                } catch (Exception ex) {
                    Vars.ui.showException("Failed to load image", ex);
                }
            });
        }).size(240, 50).row();

        ptl.addCloseButton();
        ptl.buttons.button("@settings", Icon.settings, this::showSettings);

        ptl.buttons.button("Export", Icon.export, () -> {
            Threads.daemon("PickMeAnime worker", () -> {
                try {
                    Exporter.export(currentImage);
                    Core.app.post(ptl::hide);
                } catch (Exception ex) {
                    Core.app.post(() -> {
                        Vars.ui.showException("Failed to export schematic", ex);
                    });
                }
            });
        }).disabled(b -> currentImage == null);

        ptl.show();
    }

    void showSettings() {
        BaseDialog d = new BaseDialog("@settings");
        d.cont.pane(t -> {
            t.defaults().growX().center();

            t.button("Display: " + coreDisplay.name, Icon.layers, () -> {
                BaseDialog sel = new BaseDialog("Select Display");
                Vars.content.blocks().each(b -> b instanceof LogicDisplay, b -> {
                    sel.cont.button(b.localizedName, () -> {
                        coreDisplay = (LogicDisplay) b;
                        coreSize = coreDisplay.displaySize;
                        sel.hide();
                    }).size(250, 60).row();
                });
                sel.addCloseButton();
                sel.show();
            }).height(80).row();

            t.table(s -> {
                s.add("Max strings at processor: ");
                s.field(String.valueOf(coreSpeed), str -> coreSpeed = Strings.parseInt(str, 1000));
            }).height(64).row();

            t.check("Gray Transparency", coreUseGray, b -> coreUseGray = b).row();

            t.label(() -> "Quality: " + coreQuality).row();
            t.slider(0, 255, 1, coreQuality, n -> coreQuality = (int)n).row();

            t.check("Use HSV", coreHsv, b -> coreHsv = b).disabled(b -> coreQuality == 255).row();
        }).width(400).growY();
        d.addCloseButton();
        d.show();
    }
}