import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Writes a small, standards-clean animated GIF (3 frames, loops forever).
 * Used as a test asset: the previous hand-rolled GIF was rejected by the JDK
 * GIF parser ("Unexpected block type 0").
 *
 * usage: java MakeGif <output.gif>
 */
public final class MakeGif {

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        int w = 320;
        int h = 200;
        Color[] colors = {new Color(0x2B6CF6), new Color(0xEF4444), new Color(0x10B981)};

        BufferedImage[] frames = new BufferedImage[colors.length];
        for (int i = 0; i < colors.length; i++) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(colors[i]);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 96));
            String label = String.valueOf(i + 1);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, (w - fm.stringWidth(label)) / 2,
                    h / 2 + fm.getAscent() / 2 - 10);
            g.dispose();
            frames[i] = img;
        }

        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("gif");
        if (!it.hasNext()) {
            throw new IllegalStateException("no GIF writer available");
        }
        ImageWriter writer = it.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (BufferedImage frame : frames) {
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta =
                        writer.getDefaultImageMetadata(new ImageTypeSpecifier(frame), param);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);

                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", "40"); // 40 * 10ms = 400ms per frame
                gce.setAttribute("transparentColorIndex", "0");

                IIOMetadataNode appExts = child(root, "ApplicationExtensions");
                IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                app.setAttribute("applicationID", "NETSCAPE");
                app.setAttribute("authenticationCode", "2.0");
                app.setUserObject(new byte[] {1, 0, 0}); // loop forever
                appExts.appendChild(app);

                meta.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(frame, null, meta), param);
            }
            writer.endWriteSequence();
        }
        writer.dispose();

        System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
