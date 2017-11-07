package net.makutin.image2gcode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.util.ArrayList;

public class App {

    private final static byte majorVersion = 1;
    private final static byte minorVersion = 4;
    private final static double defaultTargetWidth = 100;
    private final static double minDotLength = 0.012; // in mm
    private final static double maxDotLength = 0.05; // in mm

    public static class Parameters {
        public boolean vertical = false;
        public Double cutFeedRate = null;
        public Double moveFeedRate = null;
        public double targetOffsetX = 0;
        public double targetOffsetY = 0;
        public double targetWidth = 0;
        public double targetHeight = 0;
    }

    public static class ImageData{
        public final int width;
        public final int height;
        public final int[] data; //  using RGB color model (TYPE_INT_ARGB) and sRGB color space

        public ImageData(int width, int height, int[] data){
            this.width = width;
            this.height = height;
            this.data = data;
        }

        public static ImageData get(BufferedImage image) {
            if (null != image) {
                int width = image.getWidth();
                int height = image.getHeight();
                return new ImageData(width, height, image.getRGB(0, 0, width, height, null, 0, width));
            }
            return null;
        }

        public boolean invalid() {
            return (width <= 0 || height <= 0 || null == data || data.length < width * height);
        }

        public int get(int x, int y) {
            return data[y * width + x];
        }

        public static byte R(int bgra) {
            return (byte)((bgra >> 16) & 0xff);
        }

        public static byte G(int bgra) {
            return (byte)((bgra >> 8) & 0xff);
        }

        public static byte B(int bgra) {
            return (byte)(bgra & 0xff);
        }

        public static byte A(int bgra) {
            return (byte)((bgra >> 24) & 0xff);
        }

        public static int RGB(int bgra) {
            return bgra & 0xffffff;
        }
    }

    public static class HLine {
        public final int beginX;
        public final int endX;
        public final int y;

        public HLine(int beginX, int endX, int y) {
            this.beginX = beginX;
            this.endX = endX;
            this.y = y;
        }

        public int length() {
            return endX - beginX;
        }
    }

    public static class BitField {
        public final int width;
        public final int height;
        public final byte[] data;
        private final int w;
        private long count;

        public BitField(int width, int height) {
            this.width = width;
            this.height = height;
            this.count = 0;
            if (height > 0 && width > 0) {
                w = (width / 8) + (width % 8 > 0 ? 1 : 0);
                int l = w * height;
                data = new byte[l];
                for (int i = 0; i < l; i++)
                    data[i] = 0;
            } else {
                w = 0;
                data = null;
            }
        }

        public boolean get(int x, int y) {
            if (x < 0 || x >= width)
                return true;
            if (y < 0 || y >= height)
                return true;
            int i = y * w + (x / 8);
            int s = x % 8;
            return (data[i] & (byte)(1 << s)) != 0;
        }

        public boolean set(int x, int y, boolean v) {
            if (x < 0 || x >= width)
                return true;
            if (y < 0 || y >= height)
                return true;
            int i = w * y + (x / 8);
            int s = x % 8;
            byte m = (byte)(1 << s);
            boolean rc = (data[i] & m) != 0;
            if (v) {
                if (!rc) {
                    data[i] |= m;
                    count++;
                }
            } else {
                if (rc) {
                    data[i] &= ~m;
                    count--;
                }
            }
            return rc;
        }

        public long count() {
            return count;
        }
    }

    public static void main(String[] args) {
        String inputFile = null;
        String outputFile = null;
        boolean lineScan = false;
        Parameters params = new Parameters();
        boolean help = false;

        if (null != args && args.length > 0) {
            for (int argi = 0; argi < args.length; argi++) {
                String arg = args[argi];
                if (null == arg || arg.isEmpty()) {
                    continue;
                }

                if ("-?".equals(arg) || "/?".equals(arg) || "--help".equalsIgnoreCase(arg)) {
                    help = true;
                } else if ("-l".equalsIgnoreCase(arg) || "/l".equalsIgnoreCase(arg) || "--lineScan".equalsIgnoreCase(arg)) {
                    lineScan = true;
                } else if ("-v".equalsIgnoreCase(arg) || "/v".equalsIgnoreCase(arg) || "--vertical".equalsIgnoreCase(arg)) {
                    params.vertical = true;
                } else if ("-s".equalsIgnoreCase(arg) || "/s".equalsIgnoreCase(arg) || "--speed".equalsIgnoreCase(arg) ||
                        "-cr".equalsIgnoreCase(arg) || "/cr".equalsIgnoreCase(arg) || "--cutRate".equalsIgnoreCase(arg)) {
                    if (++argi < args.length) {
                        params.cutFeedRate = parseDouble(args[argi], params.cutFeedRate);
                    }
                } else if ("-mr".equalsIgnoreCase(arg) || "/mr".equalsIgnoreCase(arg) || "--moveRate".equalsIgnoreCase(arg)) {
                    if (++argi < args.length) {
                        params.moveFeedRate = parseDouble(args[argi], params.moveFeedRate);
                    }
                } else if ("-x".equalsIgnoreCase(arg) || "/x".equalsIgnoreCase(arg) || "--offsetX".equalsIgnoreCase(arg)) {
                    if (++argi < args.length) {
                        params.targetOffsetX = parseDouble(args[argi], 0d);
                    }
                } else if ("-y".equalsIgnoreCase(arg) || "/y".equalsIgnoreCase(arg) || "--offsetY".equalsIgnoreCase(arg)) {
                    if (++argi < args.length) {
                        params.targetOffsetY = parseDouble(args[argi], 0d);
                    }
                } else if ("-w".equalsIgnoreCase(arg) || "/w".equalsIgnoreCase(arg) || "--width".equalsIgnoreCase(arg)) {
                    if (++argi < args.length) {
                        params.targetWidth = parseDouble(args[argi], 0d);
                    }
                } else if ("-h".equalsIgnoreCase(arg) || "/h".equalsIgnoreCase(arg) || "--height".equalsIgnoreCase(arg)) {
                    if (++argi < args.length) {
                        params.targetHeight = parseDouble(args[argi], 0d);
                    }
                } else if (null == inputFile || inputFile.isEmpty()) {
                    inputFile = arg;
                } else if (null == outputFile || outputFile.isEmpty()) {
                    outputFile = arg;
                }
            }
        }

        if (!help && (null == inputFile || inputFile.isEmpty())) {
            System.out.printf("ERROR: Input file is not specified.%n%n");
            help = true;
        }

        if (help) {
            System.out.printf(
//@formatter:off
"Image to G-Code converter v%d.%d for laser engraver or similar tools.%n" +
"Converts binary (black and white) images into 2D G-Code. White color treated%n" +
"as background color, any other color - as engraving/cutting color.%n" +
"author: Stas Makutin, stas@makutin.net, 2016%n" +
"%n" +
"Usage: <input file> [<output file>] [options]%n" +
"%n" +
"Options:%n" +
"-l, /l, --lineScan%n" +
"  Generate image line-by-line instead of detecting continious regions.%n" +
"-v, /v, --vertical%n" +
"  Trace vertical lines instead of horizontal.%n" +
"-s, /s, --speed, -cr, /cr, --cutRate  <feed rate>%n" +
"  Engraving/cutting feed rate. Optional.%n" +
"-mr, /mr, --moveRate  <feed rate>%n" +
"  Moving (not cutting) feed rate. Optional.%n" +
"-x, /x, --offsetX <offset>%n" +
"  Target X-axis offset, in millimeters. Optional, default is 0.%n" +
"-y, /y, --offsetY <offset>%n" +
"  Target Y-axis offset, in millimeters. Optional, default is 0.%n" +
"-w, /w, --width <width>%n" +
"  Target width, in millimeters. If not provided then it will be calculated%n" +
"  from provided height and input image width and height. If height is not%n" +
"  provided then default width %s will be used.%n" +
"-h, /h, --height <height>%n" +
"  Target height, in millimeters. If not provided then it will be calculated%n" +
"  from target width and input image width and height.%n",
                majorVersion,
                minorVersion,
                toDecimalString(defaultTargetWidth)
//@formatter:on
            );
            System.exit(64);
        }

        Path inputPath = Paths.get(inputFile);
        Path outputPath = null;
        if (null == outputFile || outputFile.isEmpty()) {
            Path inputName = inputPath.getFileName();
            if (null != inputName) {
                outputFile = inputName.toString();
            }
            if (null != outputFile && !outputFile.isEmpty()) {
                int p = outputFile.lastIndexOf('.');
                if (p > 0) {
                    outputFile = outputFile.substring(0, p);
                }
                outputFile += ".nc";
                outputPath = inputPath.resolveSibling(outputFile);
            }
        } else {
            outputPath = Paths.get(outputFile);
        }

        if (null == outputPath) {
            System.out.printf("ERROR: Input file is not valid.%n");
            System.exit(64);
        }

        String errorMessage = "Unknown";
        try {
            System.out.println("Loading input image...");
            errorMessage = "Unable to load input image";
            ImageData image = loadImage(inputPath);
            if (null == image || image.invalid()) {
                throw new Exception("Invalid image data.");
            }

            if (params.targetWidth <= 0) {
                if (params.targetHeight > 0) {
                    params.targetWidth = ((double)image.width) * params.targetHeight / ((double)image.height);
                } else {
                    params.targetWidth = defaultTargetWidth;
                }
            }

            if (params.targetHeight <= 0) {
                params.targetHeight = ((double)image.height) * params.targetWidth / ((double)image.width);
            }

            System.out.printf(
                "Information:%n  Input file: %s%n  Output file: %s%n  Vertical trace: %s%n  Cut Feed Rate: %s%n  Move Feed Rate: %s%n  Target offset X: %s%n  Target offset Y: %s%n  Target width: %s%n  Target height: %s%n",
                inputPath.toString(),
                outputPath.toString(),
                params.vertical ? "yes" : "no",
                null == params.cutFeedRate ? "default" : toDecimalString(params.cutFeedRate),
                null == params.moveFeedRate ? "default" : toDecimalString(params.moveFeedRate),
                toDecimalString(params.targetOffsetX),
                toDecimalString(params.targetOffsetY),
                toDecimalString(params.targetWidth),
                toDecimalString(params.targetHeight)
            );

            System.out.println("Opening output file...");
            errorMessage = "Unable to open or create output file";
            try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                errorMessage = "Unable to write into output file";

                System.out.print("Generating G-Code...");
                writeHeader(writer);
                if (lineScan) {
                    writeLineScan(writer, image, params);
                } else {
                    writeContiniousScan(writer, image, params);
                }
                writeFooter(writer);
                System.out.println();
            }

        } catch (Exception e) {
            System.out.printf("Failure.%n%s. Details:%n%s", errorMessage, getErrorMsg(e));
            System.exit(1);
        }

        System.out.println("Success.");
        System.exit(0);
    }

    public static ImageData loadImage(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            return ImageData.get(ImageIO.read(stream));
        }
    }

    public static void writeHeader(BufferedWriter writer) throws IOException {
        writer.write("G90\r\n");
        writer.write("G21\r\n");
        writer.write("\r\n");
    }

    public static void writeFooter(BufferedWriter writer) throws IOException {
        writer.write("\r\n");
        writer.write("G0 X0 Y0\r\n");
        writer.write("M05\r\n");
    }

    public static void writeLineScan(BufferedWriter writer, ImageData image, Parameters params) throws IOException {
        long runSince = System.nanoTime();

        double xStep = image.width > 1 ? params.targetWidth / (double)(image.width - 1) : 0;
        double yStep = image.height > 1 ? params.targetHeight / (double)(image.height - 1) : 0;
        int scanWidth = 0;
        int scanHeight = 0;
        double dotLength = 0;
        double tx = 0;
        double ty = 0;
        int dirX = 1;
        boolean cutting = false;

        if (params.vertical) {
            tx = params.targetOffsetX;
            scanWidth = image.height;
            scanHeight = image.width;
            dotLength = setDotLength(yStep / 10);
            if (image.height > 1) {
                yStep = (params.targetHeight - dotLength)  / (double)(image.height - 1);
            }
        } else {
            ty = params.targetOffsetY;
            scanWidth = image.width;
            scanHeight = image.height;
            dotLength = setDotLength(xStep / 10);
            if (image.width > 1) {
                xStep = (params.targetWidth - dotLength)  / (double)(image.width - 1);
            }
        }

        double scanWH = scanWidth * scanHeight;
        System.out.print("\rGenerating G-Code... 0%     ");

        boolean differentRate = true;
        if (null != params.cutFeedRate && null != params.moveFeedRate && Math.abs(params.cutFeedRate - params.moveFeedRate) <= 0.001) {
            differentRate = false;
            writer.write(String.format("G1 F%s\r\n", toDecimalString(params.cutFeedRate)));
        }

        int scanY = 0;
        while (scanY < scanHeight) {
            int scanX = (dirX < 0) ? scanWidth - 1 : 0;
            boolean changeDir = false;
            while (true) {
                boolean cut = cutPixel(image, scanX, scanY, params.vertical);

                runSince = progress(scanX + scanY * scanWidth, scanWH, runSince);

                if (cutting) {
                    if (!cut) {
                        if (params.vertical) {
                            ty = params.targetOffsetY + (scanX - dirX) * yStep + dotLength;
                        } else {
                            tx = params.targetOffsetX + (scanX - dirX) * xStep + dotLength;
                        }
                        writer.write(String.format("G1 X%s Y%s\r\n", toDecimalString(tx), toDecimalString(ty)));
                        writer.write("M05\r\n");
                        cutting = false;
                    }
                } else if (cut) {
                    if (params.vertical) {
                        ty = params.targetOffsetY + scanX * yStep;
                    } else {
                        tx = params.targetOffsetX + scanX * xStep;
                    }
                    if (null != params.moveFeedRate) {
                        if (differentRate) {
                            writer.write(String.format("G1 F%s\r\n", toDecimalString(params.moveFeedRate)));
                        }
                        writer.write(String.format("G1 X%s Y%s\r\n", toDecimalString(tx), toDecimalString(ty)));
                    } else {
                        writer.write(String.format("G0 X%s Y%s\r\n", toDecimalString(tx), toDecimalString(ty)));
                    }
                    writer.write("M03\r\n");
                    if (null != params.cutFeedRate && differentRate) {
                        writer.write(String.format("G1 F%s\r\n", toDecimalString(params.cutFeedRate)));
                    }
                    cutting = true;
                    changeDir = true;
                }

                scanX = scanX + dirX;
                if (dirX < 0) {
                    if (scanX < 0)
                        break;
                } else {
                    if (scanX >= scanWidth)
                        break;
                }
            }

            if (cutting) {
                if (params.vertical) {
                    ty = params.targetOffsetY + (scanX - dirX) * yStep + dotLength;
                } else {
                    tx = params.targetOffsetX + (scanX - dirX) * xStep + dotLength;
                }
                writer.write(String.format("G1 X%s Y%s\r\n", toDecimalString(tx), toDecimalString(ty)));
                writer.write("M05\r\n");
                cutting = false;
            }

            scanY++;
            if (changeDir) {
                dirX = -dirX;
            }
            if (params.vertical) {
                tx += xStep;
            } else {
                ty += yStep;
            }
        }

        System.out.print("\rGenerating G-Code... 100%            ");
    }

    public static void writeContiniousScan(BufferedWriter writer, ImageData image, Parameters params) throws IOException {
        long runSince = System.nanoTime();

        double xStep = image.width > 1 ? params.targetWidth / (double)(image.width - 1) : 0;
        double yStep = image.height > 1 ? params.targetHeight / (double)(image.height - 1) : 0;
        double dotLength = 0;
        BitField field = null;
        ArrayList<HLine> segment = new ArrayList();

        if (params.vertical) {
            field = new BitField(image.height, image.width);
            dotLength = setDotLength(yStep / 10);
            if (image.height > 1) {
                yStep = (params.targetHeight - dotLength)  / (double)(image.height - 1);
            }
        } else {
            field = new BitField(image.width, image.height);
            dotLength = setDotLength(xStep / 10);
            if (image.width > 1) {
                xStep = (params.targetWidth - dotLength)  / (double)(image.width - 1);
            }
        }

        System.out.print("\rGenerating G-Code... 0%     ");

        boolean differentRate = true;
        if (null != params.cutFeedRate && null != params.moveFeedRate && Math.abs(params.cutFeedRate - params.moveFeedRate) <= 0.001) {
            differentRate = false;
            writer.write(String.format("G1 F%s\r\n", toDecimalString(params.cutFeedRate)));
        }

        long dots = image.width * image.height;
        int scanY = 0;
        int scanX = 0;
        while (field.count() < dots) {

            runSince = progress(field.count(), dots, runSince);

            int foundX = -1;
            int foundY = -1;
            int r = 0;
            search:
            while (field.count() < dots) {
                int minY = scanY - r;
                int maxY = scanY + r;
                int minX = scanX - r;
                int maxX = scanX + r;
                int x = scanX;
                int y = maxY;
                int fx = minX;
                int fy = scanY;
                int dx = -1;
                int dy = -1;

                while (field.count() < dots) {
                    runSince = progress(field.count(), dots, runSince);

                    if (!field.set(x, y, true)) {
                        if (cutPixel(image, x, y, params.vertical)) {
                            foundX = x;
                            foundY = y;
                            break search;
                        }
                    }

                    if (r == 0) {
                        break;
                    }
                    if (x == fx && y == fy) {
                        if (minX == fx) {
                            fx = scanX;
                            fy = minY;
                            dx = 1;
                            dy = -1;
                        } else if (minY == fy) {
                            fx = maxX;
                            fy = scanY;
                            dx = 1;
                            dy = 1;
                        } else if (maxX == fx) {
                            fx = scanX;
                            fy = maxY;
                            dx = -1;
                            dy = 1;
                        } else {
                            break;
                        }
                    }
                    x += dx;
                    y += dy;
                }

                r++;
            }

            if (foundX < 0) {
                break;
            }

            HLine firstLine = getLine(image, field, foundX, foundY, params.vertical);
            segment.add(firstLine);

            HLine ln = firstLine;
            while (true) {
                runSince = progress(field.count(), dots, runSince);
                ln = findNextLine(image, field, ln, 1, params.vertical);
                if (null == ln) {
                    break;
                }
                segment.add(0, ln);
            }
            ln = firstLine;
            while (true) {
                runSince = progress(field.count(), dots, runSince);
                ln = findNextLine(image, field, ln, -1, params.vertical);
                if (null == ln) {
                    break;
                }
                segment.add(ln);
            }

            boolean endToBegin = false;
            boolean upToDown = true;
            int lines = segment.size();
            boolean oddLines = lines % 2 != 0;
            HLine upLine = segment.get(0);
            HLine downLine = segment.get(lines - 1);

            long shortestDist = distance(upLine.beginX, upLine.y, scanX, scanY);
            foundX = oddLines ? downLine.endX : downLine.beginX;
            foundY = downLine.y;

            long dist = distance(upLine.endX, upLine.y, scanX, scanY);
            if (dist < shortestDist) {
                shortestDist = dist;
                endToBegin = true;
                foundX = oddLines ? downLine.beginX : downLine.endX;
                foundY = downLine.y;
            }

            if (lines > 1) {
                dist = distance(downLine.beginX, downLine.y, scanX, scanY);
                if (dist < shortestDist) {
                    shortestDist = dist;
                    upToDown = false;
                    endToBegin = false;
                    foundX = oddLines ? upLine.endX : upLine.beginX;
                    foundY = upLine.y;
                }

                dist = distance(downLine.endX, downLine.y, scanX, scanY);
                if (dist < shortestDist) {
                    upToDown = false;
                    endToBegin = true;
                    foundX = oddLines ? upLine.beginX : upLine.endX;
                    foundY = upLine.y;
                }
            }
            scanX = foundX;
            scanY = foundY;

            int index = upToDown ? 0 : segment.size() - 1;
            while (true) {
                HLine line = segment.get(index);
                double txb, txe, tyb, tye;

                if (params.vertical) {
                    tyb = params.targetOffsetY + line.beginX * yStep;
                    tye = params.targetOffsetY + line.endX * yStep + dotLength;
                    txb = txe = params.targetOffsetX + line.y * xStep;
                    if (endToBegin) {
                        double temp = tyb;
                        tyb = tye;
                        tye = temp;
                    }
                } else {
                    txb = params.targetOffsetX + line.beginX * xStep;
                    txe = params.targetOffsetX + line.endX * xStep + dotLength;
                    tyb = tye = params.targetOffsetY + line.y * yStep;
                    if (endToBegin) {
                        double temp = txb;
                        txb = txe;
                        txe = temp;
                    }
                }

                if (null != params.moveFeedRate) {
                    if (differentRate) {
                        writer.write(String.format("G1 F%s\r\n", toDecimalString(params.moveFeedRate)));
                    }
                    writer.write(String.format("G1 X%s Y%s\r\n", toDecimalString(txb), toDecimalString(tyb)));
                } else {
                    writer.write(String.format("G0 X%s Y%s\r\n", toDecimalString(txb), toDecimalString(tyb)));
                }
                writer.write("M03\r\n");
                if (null != params.cutFeedRate && differentRate) {
                    writer.write(String.format("G1 F%s\r\n", toDecimalString(params.cutFeedRate)));
                }
                writer.write(String.format("G1 X%s Y%s\r\n", toDecimalString(txe), toDecimalString(tye)));
                writer.write("M05\r\n");

                if (upToDown) {
                    index++;
                    if (index >= segment.size())
                        break;
                } else {
                    if (0 == index)
                        break;
                    index--;
                }
            }

            segment.clear();
        }

        System.out.print("\rGenerating G-Code... 100%            ");
    }

    public static long progress(double part, double all, long runSince) {
        if (System.nanoTime() - runSince >= 500000000L) {
            double progress = part / all * 100.0;
            System.out.printf("\rGenerating G-Code... %s%%          ", toDecimalString(progress));
            runSince = System.nanoTime();
        }
        return runSince;
    }

    public static double setDotLength(double dotLength) {
        if (dotLength < minDotLength) {
            dotLength = minDotLength;
        } else if (dotLength > maxDotLength) {
            dotLength = maxDotLength;
        }
        return dotLength;
    }

    public static HLine getLine(ImageData image, BitField field, int x, int y, boolean vertical) {
        int beginX = x;
        int endX = x;
        field.set(x, y, true);
        while (!field.set(--x, y, true) && cutPixel(image, x, y, vertical)) {
            beginX = x;
        }
        x = endX;
        while (!field.set(++x, y, true) && cutPixel(image, x, y, vertical)) {
            endX = x;
        }
        return new HLine(beginX, endX, y);
    }

    public static HLine findNextLine(ImageData image, BitField field, HLine line, int dy, boolean vertical) {
        int y = line.y + dy;
        int x = line.beginX - 1;
        HLine rc = null;

        while (x <= line.endX + 1) {
            if (!field.get(x, y) && cutPixel(image, x, y, vertical)) {
                HLine ln = getLine(image, field, x, y, vertical);
                if (null == rc) {
                    rc = ln;
                } else if (ln.length() > rc.length()) {
                    for (int lx = rc.beginX; lx <= rc.endX; lx++) {
                        field.set(lx, rc.y, false);
                    }
                    rc = ln;
                }
                x = ln.endX + 1;
            } else {
                x++;
            }
        }

        return rc;
    }

    public static boolean cutPixel(ImageData image, int x, int y, boolean vertical) {
        return cutPixel(vertical ? image.get(y, image.height - x - 1) : image.get(x, image.height - y - 1));
    }

    public static boolean cutPixel(int bgra) {
        if (ImageData.A(bgra) < 0xf0) {
            return ImageData.RGB(bgra) != 0xffffff;
        }
        return false;
    }

    public static long distance(int x1, int y1, int x2, int y2) {
        long dx = (long)(x2 - x1);
        long dy = (long)(y2 - y1);
        return dx * dx + dy * dy;
    }

    public static Double parseDouble(String src, Double defaultValue) {
        if (null != src && !src.isEmpty()) {
            try {
                return Double.parseDouble(src);
            }
            catch (Exception e) {
            }
        }
        return defaultValue;
    }

    private static DecimalFormat decimalFormat = new DecimalFormat("#.####");

    public static String toDecimalString(double value) {
        return decimalFormat.format(value);
    }

    public static String getErrorMsg(String src) {
        if (null == src || src.isEmpty()) {
            return "No error details available.";
        }
        return src;
    }

    public static String getErrorMsg(Throwable src) {
        if (null == src)
            return getErrorMsg("");
        return String.format("%s: %s", src.getClass().getName(), getErrorMsg(src.getMessage()));
    }
}
