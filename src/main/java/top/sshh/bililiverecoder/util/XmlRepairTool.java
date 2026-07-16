package top.sshh.bililiverecoder.util;

import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Conservative repair helper for BililiveRecorder danmaku XML files.
 *
 * <p>Default mode is dry-run. Use --write to generate "*.repaired.xml" files,
 * or --replace to overwrite the original after creating a "*.bak" backup.</p>
 */
public final class XmlRepairTool {

    private static final Pattern ROOT_I_PATTERN = Pattern.compile("(?is)<i(?:\\s|>)");
    private static final Pattern DANMU_PATTERN = Pattern.compile("(?is)<d(?:\\s|>)");
    private static final Pattern GIFT_PATTERN = Pattern.compile("(?is)<gift(?:\\s|/?>)");
    private static final Pattern SC_PATTERN = Pattern.compile("(?is)<sc(?:\\s|/?>)");
    private static final Pattern GUARD_PATTERN = Pattern.compile("(?is)<guard(?:\\s|/?>)");

    private XmlRepairTool() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.paths().isEmpty() || options.help()) {
            printUsage();
            return;
        }

        for (Path path : expandXmlPaths(options.paths())) {
            RepairResult result = repair(path, options.write(), options.replace());
            printResult(result);
        }
    }

    public static RepairResult repair(Path source, boolean write, boolean replace) throws IOException {
        String original = Files.readString(source, StandardCharsets.UTF_8);
        ContentRepairResult contentResult = repairContent(original);
        String repaired = contentResult.repairedText();
        Validation before = contentResult.before();
        Validation after = contentResult.after();
        boolean changed = !original.equals(repaired);
        Path output = null;
        Path backup = null;

        if (write && changed && after.valid()) {
            if (replace) {
                backup = nextAvailableSibling(source, source.getFileName() + ".bak");
                Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
                Files.writeString(source, repaired, StandardCharsets.UTF_8);
                output = source;
            } else {
                output = nextAvailableRepairPath(source);
                Files.writeString(output, repaired, StandardCharsets.UTF_8);
            }
        }

        Counts counts = countElements(after.valid() ? repaired : original);
        return new RepairResult(source, before, after, changed, write, replace, output, backup, contentResult.actions(), counts);
    }

    public static ContentRepairResult repairContent(String original) {
        Validation before = validate(original);

        List<String> actions = new ArrayList<>();
        TextChange cleaned = stripBom(original);
        String repaired = cleaned.text();
        if (cleaned.count() > 0) {
            actions.add("removedBom");
        }

        cleaned = removeIllegalXmlChars(repaired);
        repaired = cleaned.text();
        if (cleaned.count() > 0) {
            actions.add("removedIllegalXmlChars=" + cleaned.count());
        }

        cleaned = trimTrailingPartialTag(repaired);
        repaired = cleaned.text();
        if (cleaned.count() > 0) {
            actions.add("trimmedTrailingPartialTag");
        }

        if (looksLikeOpenRoot(repaired) && !hasRootEndTag(repaired)) {
            repaired = repaired.stripTrailing() + System.lineSeparator() + "</i>" + System.lineSeparator();
            actions.add("appendedMissingRootEndTag");
        }

        Validation after = validate(repaired);
        Counts counts = countElements(after.valid() ? repaired : original);
        return new ContentRepairResult(before, after, !original.equals(repaired), repaired, actions, counts);
    }

    private static List<Path> expandXmlPaths(List<Path> inputs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> stream = Files.walk(input)) {
                    stream.filter(Files::isRegularFile)
                            .filter(XmlRepairTool::isXmlFile)
                            .sorted(Comparator.naturalOrder())
                            .forEach(files::add);
                }
            } else {
                files.add(input);
            }
        }
        return files;
    }

    private static boolean isXmlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xml");
    }

    private static TextChange stripBom(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return new TextChange(text.substring(1), 1);
        }
        return new TextChange(text, 0);
    }

    private static TextChange removeIllegalXmlChars(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int removed = 0;
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            if (Character.charCount(cp) == 2) {
                i++;
            }
            if (isLegalXml10CodePoint(cp)) {
                sb.appendCodePoint(cp);
            } else {
                removed++;
            }
        }
        if (removed == 0) {
            return new TextChange(text, 0);
        }
        return new TextChange(sb.toString(), removed);
    }

    private static boolean isLegalXml10CodePoint(int cp) {
        return cp == 0x9
                || cp == 0xA
                || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static TextChange trimTrailingPartialTag(String text) {
        String stripped = text.stripTrailing();
        int lastLt = stripped.lastIndexOf('<');
        int lastGt = stripped.lastIndexOf('>');
        if (lastLt > lastGt) {
            return new TextChange(stripped.substring(0, lastLt), stripped.length() - lastLt);
        }
        return new TextChange(text, 0);
    }

    private static boolean looksLikeOpenRoot(String text) {
        return ROOT_I_PATTERN.matcher(text).find();
    }

    private static boolean hasRootEndTag(String text) {
        return text.contains("</i>");
    }

    private static Validation validate(String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            configureSecureFactory(factory);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new SilentErrorHandler());
            Document document = builder.parse(new InputSource(new StringReader(text)));
            String root = document.getDocumentElement() == null ? "" : document.getDocumentElement().getTagName();
            return new Validation("i".equals(root), "i".equals(root) ? null : "root tag is not <i>: " + root);
        } catch (Exception e) {
            return new Validation(false, e.getMessage());
        }
    }

    private static void configureSecureFactory(DocumentBuilderFactory factory) throws ParserConfigurationException {
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
            // Different XML parsers support different hardening flags.
        }
    }

    private static Path nextAvailableRepairPath(Path source) {
        String fileName = source.getFileName().toString();
        String repairedName = fileName.endsWith(".xml")
                ? fileName.substring(0, fileName.length() - 4) + ".repaired.xml"
                : fileName + ".repaired.xml";
        return nextAvailableSibling(source, repairedName);
    }

    private static Path nextAvailableSibling(Path source, String preferredName) {
        Path parent = source.getParent();
        Path candidate = parent == null ? Path.of(preferredName) : parent.resolve(preferredName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = preferredName.lastIndexOf('.');
        String base = dot >= 0 ? preferredName.substring(0, dot) : preferredName;
        String suffix = dot >= 0 ? preferredName.substring(dot) : "";
        for (int i = 1; ; i++) {
            String name = base + "-" + i + suffix;
            candidate = parent == null ? Path.of(name) : parent.resolve(name);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static Counts countElements(String text) {
        return new Counts(count(DANMU_PATTERN, text), count(GIFT_PATTERN, text), count(SC_PATTERN, text), count(GUARD_PATTERN, text));
    }

    private static int count(Pattern pattern, CharSequence text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static void printResult(RepairResult result) {
        System.out.println("file=" + result.source());
        System.out.println("  beforeValid=" + result.before().valid()
                + (result.before().message() == null ? "" : " | error=" + result.before().message()));
        System.out.println("  afterValid=" + result.after().valid()
                + (result.after().message() == null ? "" : " | error=" + result.after().message()));
        System.out.println("  changed=" + result.changed() + " | write=" + result.write() + " | replace=" + result.replace());
        System.out.println("  actions=" + (result.actions().isEmpty() ? "-" : String.join(",", result.actions())));
        System.out.println("  counts=danmu:" + result.counts().danmu()
                + ",gift:" + result.counts().gift()
                + ",sc:" + result.counts().sc()
                + ",guard:" + result.counts().guard());
        if (result.backup() != null) {
            System.out.println("  backup=" + result.backup());
        }
        if (result.output() != null) {
            System.out.println("  output=" + result.output());
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java top.sshh.bililiverecoder.util.XmlRepairTool [--write] [--replace] <xml-file-or-dir>...");
        System.out.println("  default: dry-run only");
        System.out.println("  --write: generate *.repaired.xml when repair is valid");
        System.out.println("  --replace: overwrite original after creating *.bak (implies --write)");
    }

    private record Options(boolean help, boolean write, boolean replace, List<Path> paths) {
        private static Options parse(String[] args) {
            boolean help = false;
            boolean write = false;
            boolean replace = false;
            List<Path> paths = new ArrayList<>();
            for (String arg : args) {
                switch (arg) {
                    case "-h", "--help" -> help = true;
                    case "--write" -> write = true;
                    case "--replace" -> {
                        write = true;
                        replace = true;
                    }
                    default -> paths.add(Path.of(arg));
                }
            }
            return new Options(help, write, replace, paths);
        }
    }

    private record TextChange(String text, int count) {
    }

    public record Validation(boolean valid, String message) {
    }

    public record Counts(int danmu, int gift, int sc, int guard) {
    }

    public record RepairResult(
            Path source,
            Validation before,
            Validation after,
            boolean changed,
            boolean write,
            boolean replace,
            Path output,
            Path backup,
            List<String> actions,
            Counts counts
    ) {
    }

    public record ContentRepairResult(
            Validation before,
            Validation after,
            boolean changed,
            String repairedText,
            List<String> actions,
            Counts counts
    ) {
    }

    public record StreamRepairResult(
            Path tempFile,
            Validation before,
            Validation after,
            boolean changed,
            List<String> actions,
            Counts counts
    ) {
    }

    /**
     * Result from {@link #filterLegalChars(char[], int, char)}:
     * number of chars written into the buffer and a high surrogate
     * deferred to the next chunk (0 = none).
     */
    record CharFilterResult(int writePos, char pendingHigh) {
    }

    /**
     * Stream-based XML repair that minimizes memory pressure by processing
     * the input stream in 64KB chunks, writing repaired content directly
     * to a temporary file.
     *
     * @param in the InputStream to repair (caller should close it)
     * @return StreamRepairResult with path to the temporary repaired file
     * @throws IOException if I/O fails
     */
    public static StreamRepairResult streamRepair(InputStream in) throws IOException {
        Path tempFile = Files.createTempFile("xml-repair-", ".xml");
        try {
            List<String> actions = new ArrayList<>();
            boolean hasRootStart = false;
            boolean hasRootEnd = false;

            // Phase 1: stream filter into temp file
            try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 65536);
                 Writer writer = new BufferedWriter(new OutputStreamWriter(
                         new BufferedOutputStream(Files.newOutputStream(tempFile)), StandardCharsets.UTF_8), 65536)) {

                // Check and skip BOM
                reader.mark(1);
                int firstChar = reader.read();
                if (firstChar == 0xFEFF) {
                    actions.add("removedBom");
                } else if (firstChar != -1) {
                    reader.reset();
                }

                char[] buf = new char[65536];
                char pendingHigh = 0;
                int len;
                String overlap = ""; // tail of previous chunk for cross-boundary detection
                while ((len = reader.read(buf)) != -1) {
                    CharFilterResult result = filterLegalChars(buf, len, pendingHigh);
                    int writePos = result.writePos();
                    pendingHigh = result.pendingHigh();
                    int removed = len - writePos - (pendingHigh != 0 ? 1 : 0);

                    if (writePos > 0) {
                        String chunk = new String(buf, 0, writePos);

                        // Track <i> root tag with overlap to avoid
                        // missing a tag split across chunk boundaries
                        if (!hasRootStart || !hasRootEnd) {
                            String window = overlap + chunk;
                            if (!hasRootStart && ROOT_I_PATTERN.matcher(window).find()) {
                                hasRootStart = true;
                            }
                            if (!hasRootEnd && window.contains("</i>")) {
                                hasRootEnd = true;
                            }
                        }

                        // Update overlap: keep the last 16 chars (enough
                        // to cover any tag: <i is 2 chars, </i> is 4 chars)
                        int overlapLen = Math.min(16, writePos);
                        overlap = chunk.substring(writePos - overlapLen);

                        writer.write(buf, 0, writePos);
                    }

                    if (removed > 0 && !actions.contains("removedIllegalXmlChars")) {
                        actions.add("removedIllegalXmlChars");
                    }
                }
                writer.flush();
            } // close reader/writer (Phase 1 end)

            // Phase 2: trim trailing partial tag using byte-level detection.
            // < and > are ASCII (0x3C, 0x3E) and never appear inside a
            // well-formed UTF-8 multi-byte sequence, so scanning bytes is safe.
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                long fileLen = raf.length();
                if (fileLen > 0) {
                    // Read last 64KB as bytes to find trailing partial tag
                    int tailLen = (int) Math.min(fileLen, 65536L);
                    long tailStart = fileLen - tailLen;
                    raf.seek(tailStart);
                    byte[] tail = new byte[tailLen];
                    raf.readFully(tail);

                    int lastLt = -1, lastGt = -1;
                    for (int i = tail.length - 1; i >= 0; i--) {
                        if (lastLt < 0 && tail[i] == '<') lastLt = i;
                        if (lastGt < 0 && tail[i] == '>') lastGt = i;
                        if (lastLt >= 0 && lastGt >= 0) break;
                    }

                    if (lastLt > lastGt) {
                        raf.setLength(tailStart + lastLt);
                        actions.add("trimmedTrailingPartialTag");
                    }
                }
            }

            // Phase 3: append missing root end tag
            if (hasRootStart && !hasRootEnd) {
                try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                        new BufferedOutputStream(Files.newOutputStream(tempFile, StandardOpenOption.APPEND)),
                        StandardCharsets.UTF_8))) {
                    writer.write(System.lineSeparator() + "</i>" + System.lineSeparator());
                }
                actions.add("appendedMissingRootEndTag");
            }

            // Phase 4: count elements from the repaired temp file
            Counts counts = countElementsFromFile(tempFile);

            // Phase 5: SAX validation
            Validation after = validateSax(tempFile);
            Validation before = new Validation(false, "streaming mode - before validation not available");

            return new StreamRepairResult(tempFile, before, after,
                    !actions.isEmpty(), actions, counts);
        } catch (Exception e) {
            // Clean up temp file on failure
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /**
     * Filter chars[] in-place: keep only legal XML 1.0 characters
     * (0x9, 0xA, 0xD, [0x20, 0xD7FF], [0xE000, 0xFFFD]) and surrogate pairs.
     * <p>
     * When a high surrogate is the last char in {@code buf} (i.e. the pair
     * is split across chunk boundaries), it is not written and instead returned
     * as {@link CharFilterResult#pendingHigh()} to be prepended to the next chunk.
     *
     * @param buf         the chunk buffer (modified in-place)
     * @param len         number of valid chars in the buffer
     * @param pendingHigh high surrogate deferred from the previous chunk, or 0
     * @return write position and optionally a high surrogate for the next chunk
     */
    static CharFilterResult filterLegalChars(char[] buf, int len, char pendingHigh) {
        int w = 0;
        int r = 0;

        // Consume pending high surrogate from previous chunk
        if (pendingHigh != 0) {
            if (len > 0 && Character.isLowSurrogate(buf[0])) {
                buf[w++] = pendingHigh;
                buf[w++] = buf[0];
                r = 1;
            }
            // else: orphaned high surrogate — discard
        }

        for (int i = r; i < len; i++) {
            char c = buf[i];
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < len) {
                    if (Character.isLowSurrogate(buf[i + 1])) {
                        buf[w++] = c;
                        i++;
                        buf[w++] = buf[i]; // low surrogate
                    }
                    // else: orphan high surrogate followed by non-low — skip
                } else {
                    // High surrogate at end of buffer — defer to next chunk
                    return new CharFilterResult(w, c);
                }
            } else if (isLegalXml10Char(c)) {
                buf[w++] = c;
            }
            // else: skip illegal char
        }
        return new CharFilterResult(w, '\0');
    }

    /**
     * From XML 1.0 spec: allowed chars are #x9 | #xA | #xD |
     * [#x20-#xD7FF] | [#xE000-#xFFFD]
     */
    private static boolean isLegalXml10Char(char c) {
        return c == 0x9 || c == 0xA || c == 0xD
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD);
    }

    /**
     * Count danmaku elements by scanning the repaired file in 64KB chunks,
     * with 16-char overlap to handle patterns split across chunk boundaries.
     * Double-counting is avoided by subtracting matches found in the
     * overlap-only portion.
     */
    static Counts countElementsFromFile(Path file) throws IOException {
        int danmu = 0, gift = 0, sc = 0, guard = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BufferedInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8), 65536)) {
            char[] buf = new char[65536];
            int len;
            String overlap = "";
            while ((len = reader.read(buf)) != -1) {
                String chunk = new String(buf, 0, len);
                String window = overlap + chunk;

                danmu += count(DANMU_PATTERN, window) - count(DANMU_PATTERN, overlap);
                gift += count(GIFT_PATTERN, window) - count(GIFT_PATTERN, overlap);
                sc += count(SC_PATTERN, window) - count(SC_PATTERN, overlap);
                guard += count(GUARD_PATTERN, window) - count(GUARD_PATTERN, overlap);

                int overlapLen = Math.min(16, len);
                overlap = new String(buf, len - overlapLen, overlapLen);
            }
        }
        return new Counts(danmu, gift, sc, guard);
    }

    /**
     * SAX-based XML 1.0 well-formedness validation against an existing file.
     * Uses the same local-entity-safe configuration as the DOM validator.
     */
    public static Validation validateSax(Path file) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setNamespaceAware(false);

            SAXParser parser = factory.newSAXParser();
            XMLReader reader = parser.getXMLReader();
            reader.setErrorHandler(new SilentErrorHandler());

            AtomicReference<String> rootElement = new AtomicReference<>();
            reader.setContentHandler(new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName,
                                         String qName, Attributes attributes) {
                    if (rootElement.get() == null) {
                        rootElement.set(("".equals(localName) ? qName : localName).intern());
                    }
                }
            });

            try (InputStream stream = Files.newInputStream(file)) {
                reader.parse(new InputSource(stream));
            }

            String root = rootElement.get();
            if (root == null) {
                return new Validation(false, "root element not found");
            }
            if (!"i".equals(root)) {
                return new Validation(false, "root tag is not <i>: " + root);
            }
            return new Validation(true, "valid");
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.length() > 200) {
                message = message.substring(0, 200) + "...";
            }
            return new Validation(false, message != null ? message : "unknown SAX error");
        }
    }

    private static final class SilentErrorHandler implements ErrorHandler {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
