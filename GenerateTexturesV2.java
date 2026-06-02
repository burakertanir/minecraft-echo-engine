import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class GenerateTexturesV2 {

    public static void main(String[] args) throws Exception {
        String dirPath = "src/main/resources/assets/audiophilecraft/textures/block/";
        new File(dirPath).mkdirs();

        drawSubwooferGrille(new File(dirPath + "subwoofer.png"));
        drawMidRange(new File(dirPath + "mid_range.png"));
        drawLineArray(new File(dirPath + "line_array.png"));
        drawSpeakerSide(new File(dirPath + "speaker_side.png"));

        System.out.println("Generated 32x32 textures in " + dirPath);
    }

    private static void drawCabinetBase(Graphics2D g) {
        g.setColor(new Color(25, 25, 25)); // Slightly darker premium charcoal
        g.fillRect(0, 0, 32, 32);
        
        // Wood/Metal grain effect (subtle noise)
        for(int x=0; x<32; x++) {
            for(int y=0; y<32; y++) {
                if((x+y)%2 == 0) {
                    g.setColor(new Color(28, 28, 28));
                    g.fillRect(x, y, 1, 1);
                }
            }
        }

        // Inner shadow / bevel
        g.setColor(new Color(15, 15, 15));
        g.fillRect(0, 0, 32, 1);
        g.fillRect(0, 0, 1, 32);
        
        g.setColor(new Color(35, 35, 35));
        g.fillRect(31, 0, 1, 32);
        g.fillRect(0, 31, 32, 1);
    }

    private static void drawSpeakerSide(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        drawCabinetBase(g);
        
        // Add some panel lines or screws to make it look like a real cabinet side
        g.setColor(new Color(10, 10, 10));
        g.fillOval(2, 2, 2, 2);
        g.fillOval(28, 2, 2, 2);
        g.fillOval(2, 28, 2, 2);
        g.fillOval(28, 28, 2, 2);
        
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    private static void drawSubwooferGrille(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        drawCabinetBase(g);
        
        // Closed Grille Design (Mesh pattern)
        g.setColor(new Color(10, 10, 10));
        g.fillRect(3, 3, 26, 26);
        
        // Tight honeycomb/mesh dots
        g.setColor(new Color(20, 20, 20));
        for(int x=4; x<29; x+=2) {
            for(int y=4; y<29; y+=2) {
                g.fillRect(x, y, 1, 1);
                g.fillRect(x+1, y+1, 1, 1);
            }
        }
        
        // Subtle reflection on the mesh to give it depth
        g.setColor(new Color(255, 255, 255, 10));
        g.fillPolygon(new int[]{4, 20, 4}, new int[]{4, 4, 20}, 3);
        
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    private static void drawMidRange(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawCabinetBase(g);
        
        int bcy = 21, bcx = 16, br = 18;
        g.setColor(new Color(20, 20, 20));
        g.fillOval(bcx - br/2, bcy - br/2, br, br);
        g.setColor(new Color(45, 45, 45)); // Lighter cone for contrast
        g.fillOval(bcx - (br-4)/2, bcy - (br-4)/2, br-4, br-4);
        g.setColor(new Color(15, 15, 15));
        g.fillOval(bcx - 3, bcy - 3, 6, 6);
        
        int tcy = 9, tcx = 16, tr = 10;
        g.setColor(new Color(20, 20, 20));
        g.fillOval(tcx - tr/2, tcy - tr/2, tr, tr);
        g.setColor(new Color(80, 80, 80));
        g.fillOval(tcx - 2, tcy - 2, 4, 4);
        
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    private static void drawLineArray(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        drawCabinetBase(g);
        
        g.setColor(new Color(12, 12, 12));
        g.fillRect(3, 3, 26, 26);
        
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for(int i=0; i<4; i++) {
            int y = 5 + i * 6;
            g.setColor(new Color(30, 30, 30));
            g.fillOval(6, y, 6, 6);
            g.fillOval(20, y, 6, 6);
            g.setColor(new Color(20, 20, 20));
            g.fillRect(14, y, 4, 6);
        }
        
        // Industrial Grille overlay
        g.setColor(new Color(0, 0, 0, 100));
        for(int y=4; y<29; y+=2) {
            g.fillRect(4, y, 24, 1);
        }
        
        g.dispose();
        ImageIO.write(img, "png", out);
    }
}
