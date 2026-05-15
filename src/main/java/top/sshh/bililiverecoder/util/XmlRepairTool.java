package top.sshh.bililiverecoder.util;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private static int count(Pattern pattern, String text) {
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
