import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import java.awt.Color;

public class TextureGenerator {
    public static void main(String[] args) {
        int width = 16;
        int height = 16;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int T = 0x00000000; // Transparent
        int B = 0xFF111111; // Black Border
        int G = 0xFF2A2A2A; // Dark Gray Frame
        int W = 0xFFE0E0E0; // White Screen
        int H = 0xFFFFFFFF; // Highlight
        int D = 0xFF000000; // Dark Screen Details

        int[][] pixels = {
                { T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T },
                { T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T },
                { T, T, T, T, B, B, B, B, B, B, B, B, T, T, T, T },
                { T, T, T, B, G, G, G, G, G, G, G, G, B, T, T, T },
                { T, T, B, G, G, G, G, G, G, G, G, G, G, B, T, T },
                { T, T, B, G, H, H, H, H, H, H, H, W, G, B, T, T },
                { T, T, B, G, H, W, W, W, W, W, W, W, G, B, T, T },
                { T, T, B, G, H, W, W, D, W, W, W, W, G, B, T, T },
                { T, T, B, G, H, W, W, D, D, W, W, W, G, B, T, T },
                { T, T, B, G, H, W, W, D, W, W, W, W, G, B, T, T },
                { T, T, B, G, H, W, W, W, W, W, W, W, G, B, T, T },
                { T, T, B, G, H, W, W, W, W, W, W, W, G, B, T, T },
                { T, T, B, G, G, G, G, B, B, G, G, G, G, B, T, T },
                { T, T, T, B, G, G, G, G, G, G, G, G, B, T, T, T },
                { T, T, T, T, B, B, B, B, B, B, B, B, T, T, T, T },
                { T, T, T, T, T, T, T, T, T, T, T, T, T, T, T, T }
        };

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, pixels[y][x]);
            }
        }

        try {
            File output = new File("src/main/resources/assets/audiophilecraft/textures/item/amplifier_tablet.png");
            ImageIO.write(img, "png", output);
            System.out.println("Tablet texture generated successfully.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
