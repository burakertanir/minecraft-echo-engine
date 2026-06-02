import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class GenerateTextures {

    public static void main(String[] args) throws Exception {
        String dirPath = "src/main/resources/assets/audiophilecraft/textures/block/";
        new File(dirPath).mkdirs();

        drawSubwoofer(new File(dirPath + "subwoofer.png"));
        drawMidRange(new File(dirPath + "mid_range.png"));
        drawLineArray(new File(dirPath + "line_array.png"));

        System.out.println("Generated 32x32 textures in " + dirPath);
    }

    private static void drawCabinetBase(Graphics2D g) {
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, 32, 32);
        
        // Inner shadow / bevel
        g.setColor(new Color(20, 20, 20));
        g.fillRect(0, 0, 32, 1);
        g.fillRect(0, 0, 1, 32);
        
        g.setColor(new Color(40, 40, 40));
        g.fillRect(31, 0, 1, 32);
        g.fillRect(0, 31, 32, 1);
        
        // Cabinet screws in corners
        g.setColor(new Color(15, 15, 15));
        g.fillOval(1, 1, 2, 2);
        g.fillOval(29, 1, 2, 2);
        g.fillOval(1, 29, 2, 2);
        g.fillOval(29, 29, 2, 2);
    }

    private static void drawSubwoofer(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        drawCabinetBase(g);
        
        // Giant Subwoofer Cone (26x26)
        int cx = 16, cy = 16;
        int r1 = 26; // Outer ring
        g.setColor(new Color(22, 22, 22));
        g.fillOval(cx - r1/2, cy - r1/2, r1, r1);
        
        // Inner rubber surround
        int r2 = 24;
        g.setColor(new Color(15, 15, 15));
        g.fillOval(cx - r2/2, cy - r2/2, r2, r2);
        
        // Cone gradient
        int r3 = 20;
        RadialGradientPaint rgp = new RadialGradientPaint(cx, cy, r3/2f, new float[]{0f, 1f}, new Color[]{new Color(35, 35, 35), new Color(12, 12, 12)});
        g.setPaint(rgp);
        g.fillOval(cx - r3/2, cy - r3/2, r3, r3);
        
        // Dust cap
        int r4 = 10;
        g.setColor(new Color(25, 25, 25));
        g.fillOval(cx - r4/2, cy - r4/2, r4, r4);
        g.setColor(new Color(15, 15, 15));
        g.drawOval(cx - r4/2, cy - r4/2, r4, r4);
        
        // Subwoofer reflection logic
        g.setColor(new Color(255, 255, 255, 15));
        g.fillArc(cx - r3/2, cy - r3/2, r3, r3, 45, 90);
        
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    private static void drawMidRange(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        drawCabinetBase(g);
        
        // Two mid cones + one tweeter? Or just a robust Mid-range block. 
        // Let's do a classic large 16px bottom cone and a 10px top tweeter.
        
        int bcy = 21, bcx = 16, br = 18; // Bottom Cone
        g.setColor(new Color(22, 22, 22));
        g.fillOval(bcx - br/2, bcy - br/2, br, br);
        g.setColor(new Color(15, 15, 15));
        g.fillOval(bcx - (br-2)/2, bcy - (br-2)/2, br-2, br-2);
        
        RadialGradientPaint coneGrad = new RadialGradientPaint(bcx, bcy, (br-4)/2f, new float[]{0f, 1f}, new Color[]{new Color(40, 40, 40), new Color(10, 10, 10)});
        g.setPaint(coneGrad);
        g.fillOval(bcx - (br-4)/2, bcy - (br-4)/2, br-4, br-4);
        // Mid cap
        g.setColor(new Color(30, 30, 30));
        g.fillOval(bcx - 3, bcy - 3, 6, 6);
        
        
        int tcy = 9, tcx = 16, tr = 10; // Top Tweeter
        g.setColor(new Color(25, 25, 25));
        g.fillOval(tcx - tr/2, tcy - tr/2, tr, tr);
        
        g.setColor(new Color(10, 10, 10));
        g.fillOval(tcx - (tr-2)/2, tcy - (tr-2)/2, tr-2, tr-2);
        
        // Tweeter dome
        g.setColor(new Color(70, 70, 70));
        g.fillOval(tcx - 2, tcy - 2, 4, 4);
        
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    private static void drawLineArray(File out) throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        
        drawCabinetBase(g);
        
        // Line Array is usually heavily grilled with multiple horizontal line segments
        // We'll give it a distinct rugged front metal grille with two columns of speakers visible behind it
        
        // Base dark hole
        g.setColor(new Color(12, 12, 12));
        g.fillRect(3, 3, 26, 26);
        
        // Speakers inside (4 pairs of tiny drivers)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(25, 25, 25));
        for(int i=0; i<4; i++) {
            int y = 5 + i * 6;
            g.fillOval(6, y, 6, 6);
            g.fillOval(20, y, 6, 6);
            // Center horn
            g.setColor(new Color(18, 18, 18));
            g.fillRect(14, y, 4, 6);
            g.setColor(new Color(25, 25, 25));
        }
        
        // Overlaid Grille
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(10, 10, 10, 150));
        for(int x = 4; x < 28; x += 2) {
            for(int y = 4; y < 28; y += 2) {
                g.fillRect(x, y, 1, 1);
            }
        }
        
        // Rigid borders for line array
        g.setColor(new Color(50, 50, 50));
        g.fillRect(2, 2, 28, 1);
        g.fillRect(2, 29, 28, 1);
        g.fillRect(2, 2, 1, 28);
        g.fillRect(29, 2, 1, 28);
        
        g.dispose();
        ImageIO.write(img, "png", out);
    }
}
