import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Host-only asset validation/encoding. No generated artwork or Android code. */
public final class Branding {
    private static BufferedImage read(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Required branding asset missing: " + file);
        BufferedImage image = ImageIO.read(file);
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > 8192 || image.getHeight() > 8192)
            throw new IOException("Invalid or oversized branding image: " + file);
        return image;
    }
    public static void main(String[] args) throws Exception {
        BufferedImage source = read(new File(args[0]));
        read(new File(args[1])); // Remote banner must also be a real source image.
        File output = new File(args[2]);
        output.getParentFile().mkdirs();
        // The supplied icon has a .png filename but JPEG bytes. Normalize only
        // its encoding; dimensions, decoded pixels and source file stay intact.
        if (!ImageIO.write(source, "png", output)) throw new IOException("PNG encoder unavailable");
        BufferedImage encoded = read(output);
        for (int y = 0; y < source.getHeight(); y++)
            for (int x = 0; x < source.getWidth(); x++)
                if (source.getRGB(x, y) != encoded.getRGB(x, y))
                    throw new IOException("Branding conversion changed source pixels");
        System.out.println("Branding validated: " + source.getWidth() + "x" + source.getHeight() + "; runtime PNG preserves decoded pixels");
    }
}
