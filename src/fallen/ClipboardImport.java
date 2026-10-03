package fallen;

import arc.graphics.Pixmap;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;

// Всё, что касается AWT, вынесено сюда: класс загружается только на ПК,
// на Android (где java.awt нет) он даже не подгружается
public class ClipboardImport {

    // null — в буфере обмена нет картинки
    public static Pixmap read() throws Exception {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable content = cb.getContents(null);

        if (content == null || !content.isDataFlavorSupported(DataFlavor.imageFlavor)) return null;
        return awtToPixmap((Image) content.getTransferData(DataFlavor.imageFlavor));
    }

    // Конвертация системной картинки AWT в понятный игре Pixmap
    private static Pixmap awtToPixmap(Image img) {
        BufferedImage bimg;
        if (img instanceof BufferedImage) {
            bimg = (BufferedImage) img;
        } else {
            // У не до конца загруженной картинки getWidth() == -1; ImageIcon дожидается загрузки
            img = new javax.swing.ImageIcon(img).getImage();
            int w = img.getWidth(null), h = img.getHeight(null);
            if (w <= 0 || h <= 0) throw new IllegalStateException("Clipboard image could not be loaded");

            bimg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bimg.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }

        int w = bimg.getWidth(), h = bimg.getHeight();
        Pixmap pix = new Pixmap(w, h);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = bimg.getRGB(x, y);
                // Переводим из формата AWT (ARGB) в формат Mindustry (RGBA8888)
                pix.set(x, y, (argb << 8) | (argb >>> 24));
            }
        }
        return pix;
    }
}
