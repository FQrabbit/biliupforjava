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
 * 用于 BililiveRecorder 弹幕 XML 文件的保守修复工具
 *
 * <p>默认模式为试运行（dry-run）。使用 --write 生成 "*.repaired.xml" 文件，
 * 或使用 --replace 在创建 "*.bak" 备份后覆盖原文件</p>
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

        Counts counts = contentResult.counts();
        return new RepairResult(source, before, after, changed, write, replace, output, backup, contentResult.actions(), counts);
    }

    public static ContentRepairResult repairContent(String original) {
        try {
            var result = streamRepair(new java.io.ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)));
            try {
                List<String> actions = new ArrayList<>(result.actions());
                if (result.changed()) actions.add(String.valueOf(result.report().get("summary")));
                return new ContentRepairResult(result.before(), result.after(), result.changed(),
                        Files.readString(result.tempFile(), StandardCharsets.UTF_8), actions, result.counts());
            } finally {
                Files.deleteIfExists(result.tempFile());
            }
        } catch (IOException e) {
            return new ContentRepairResult(validate(original), new Validation(false, e.getMessage()),
                    false, original, List.of(), countElements(original));
        }
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
            // 不同的 XML 解析器所支持的加固开关各不相同
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
            Counts counts,
            java.util.Map<String, Object> report
    ) {
    }

    /**
     * {@link #filterLegalChars(char[], int, char)} 的返回结果：
     * 写入缓冲区的字符数，以及延迟到下一个分块的高代理项（0 表示没有）
     */
    record CharFilterResult(int writePos, char pendingHigh) {
    }

    /**
     * 基于流的 XML 修复：以 64KB 分块处理输入流，将修复后的内容直接写入临时文件，
     * 从而把内存占用降到最低
     *
     * @param in 待修复的输入流（由调用方负责关闭）
     * @return StreamRepairResult，其中包含修复后临时文件的路径
     * @throws IOException 当 I/O 失败时抛出
     */
    public static StreamRepairResult streamRepair(InputStream in) throws IOException {
        return RecorderXmlRecovery.repair(in);
    }

    /**
     * 就地过滤 chars[]：只保留合法的 XML 1.0 字符
     * （0x9、0xA、0xD、[0x20, 0xD7FF]、[0xE000, 0xFFFD]）以及代理项对
     * <p>
     * 当高代理项是 {@code buf} 中的最后一个字符时（即代理项对被分块边界拆开），
     * 该字符不会被写出，而是通过 {@link CharFilterResult#pendingHigh()} 返回，
     * 以便前插到下一个分块中
     *
     * @param buf         分块缓冲区（就地修改），在需要携带代理项时需预留一个空位
     * @param len         缓冲区中有效字符的数量
     * @param pendingHigh 从上一个分块延迟下来的高代理项，或 0
     * @return 写入位置，以及可选的、供下一个分块使用的高代理项
     */
    static CharFilterResult filterLegalChars(char[] buf, int len, char pendingHigh) {
        int w = 0;
        // 在不覆盖未读取输入的前提下前插；这样该代理项对就能按常规方式处理
        if (pendingHigh != 0) {
            if (len > 0 && Character.isLowSurrogate(buf[0])) {
                System.arraycopy(buf, 0, buf, 1, len);
                buf[0] = pendingHigh;
                len++;
            }
            // 否则：孤立的高代理项 —— 直接丢弃
        }

        for (int i = 0; i < len; i++) {
            char c = buf[i];
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < len) {
                    if (Character.isLowSurrogate(buf[i + 1])) {
                        buf[w++] = c;
                        i++;
                        buf[w++] = buf[i]; // 低代理项
                    }
                    // 否则：孤立的高代理项后面跟着非低代理项 —— 跳过
                } else {
                    // 高代理项位于缓冲区末尾 —— 延迟到下一个分块处理
                    return new CharFilterResult(w, c);
                }
            } else if (isLegalXml10Char(c)) {
                buf[w++] = c;
            }
            // 否则：跳过非法字符
        }
        return new CharFilterResult(w, '\0');
    }

    /**
     * 依据 XML 1.0 规范：允许的字符为 #x9 | #xA | #xD |
     * [#x20-#xD7FF] | [#xE000-#xFFFD]
     */
    private static boolean isLegalXml10Char(char c) {
        return c == 0x9 || c == 0xA || c == 0xD
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD);
    }

    /**
     * 以 64KB 分块扫描修复后的文件来统计弹幕元素数量，
     * 分块之间重叠 16 个字符，以处理被分块边界拆开的匹配模式
     * 通过扣除仅出现在重叠部分中的匹配，避免重复计数
     */
    static Counts countElementsFromFile(Path file) throws IOException {
        int[] counts = new int[4];
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setContentHandler(new DefaultHandler() {
                int depth;
                @Override public void startElement(String uri, String local, String tag, Attributes attributes) {
                    if (depth++ == 1) {
                        switch (tag) {
                            case "d" -> counts[0]++;
                            case "gift" -> counts[1]++;
                            case "sc" -> counts[2]++;
                            case "guard" -> counts[3]++;
                        }
                    }
                }
                @Override public void endElement(String uri, String local, String tag) { depth--; }
            });
            try (var input = Files.newInputStream(file)) { reader.parse(new InputSource(input)); }
            return new Counts(counts[0], counts[1], counts[2], counts[3]);
        } catch (Exception e) { throw new IOException("XML 事件计数失败", e); }
    }

    /**
     * 基于 SAX 的 XML 1.0 良构性校验，针对已存在的文件
     * 使用与 DOM 校验器相同的本地实体安全配置
     */
    public static Validation validateSax(Path file) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
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