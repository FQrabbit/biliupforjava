package top.sshh.bililiverecoder.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** 用于恢复按行组织的 BililiveRecorder 格式；绝不猜测身份或金额 */
final class RecorderXmlRecovery {
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final List<String> TYPES = List.of("d", "gift", "sc", "guard");
    private static final Pattern START = Pattern.compile("^\\s*<(d|gift|sc|guard)(?=\\s|/?>)");
    private static final Pattern META = Pattern.compile("^\\s*<(chatserver|chatid|mission|maxlimit|state|real_name|source|BililiveRecorder|BililiveRecorderRecordInfo)(?=\\s|/?>)");
    private final DocumentBuilder parser;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, Integer> kept = counters(), recovered = counters(), discarded = counters();
    private final Map<String, Integer> reasons = new LinkedHashMap<>();
    private int unknownFragments;
    private boolean headerRebuilt;

    private RecorderXmlRecovery() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
    }

    static XmlRepairTool.StreamRepairResult repair(InputStream input) throws IOException {
        Path source = Files.createTempFile("xml-source-", ".xml");
        Path output = null;
        boolean returned = false;
        try {
            Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
            var before = XmlRepairTool.validateSax(source);
            if (before.valid()) {
                var counts = XmlRepairTool.countElementsFromFile(source);
                returned = true;
                return new XmlRepairTool.StreamRepairResult(source, before, before, false, List.of(), counts,
                        Map.of("mode", "UNCHANGED", "summary", "文件本身合法，未修改任何字节"));
            }
            output = Files.createTempFile("xml-recovery-", ".xml");
            RecorderXmlRecovery recovery = new RecorderXmlRecovery();
            recovery.recover(source, output);
            var after = XmlRepairTool.validateSax(output);
            int retained = recovery.kept.values().stream().mapToInt(Integer::intValue).sum();
            int restored = recovery.recovered.values().stream().mapToInt(Integer::intValue).sum();
            int dropped = recovery.discarded.values().stream().mapToInt(Integer::intValue).sum();
            if (retained + restored == 0) after = new XmlRepairTool.Validation(false, "没有找到可确认完整的录播姬事件，拒绝输出空的恢复文件");
            String summary = (dropped > 0 || recovery.unknownFragments > 0 || recovery.headerRebuilt ? "部分恢复：" : "恢复完成：") + "保留 " + retained + " 条，恢复 " + restored + " 条，丢弃 " + dropped + " 条已识别事件";
            if (recovery.unknownFragments > 0) summary += "，另有 " + recovery.unknownFragments + " 处无法识别的片段（损失条数未知）";
            if (recovery.headerRebuilt) summary += "；文件头损坏，已重建，部分元信息或显示样式可能丢失";
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("mode", "RECOVERED");
            report.put("kept", recovery.kept);
            report.put("recovered", recovery.recovered);
            report.put("discarded", recovery.discarded);
            report.put("reasons", recovery.reasons);
            report.put("unknownFragments", recovery.unknownFragments);
            report.put("headerRebuilt", recovery.headerRebuilt);
            report.put("lossy", dropped > 0 || recovery.unknownFragments > 0 || recovery.headerRebuilt);
            report.put("summary", summary);
            var counts = new XmlRepairTool.Counts(recovery.total("d"), recovery.total("gift"), recovery.total("sc"), recovery.total("guard"));
            returned = true;
            return new XmlRepairTool.StreamRepairResult(output, before, after, true,
                    List.of("逐条验证并恢复录播姬记录"), counts, report);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("文件包含无效 UTF-8 字节，无法可靠判断损坏内容；未进行替换", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("XML 恢复失败：" + e.getMessage(), e);
        } finally {
            if (!returned || output != null) Files.deleteIfExists(source);
            if (!returned && output != null) Files.deleteIfExists(output);
        }
    }

    private void recover(Path source, Path output) throws Exception {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(source), decoder));
             var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            StringBuilder header = new StringBuilder(), record = new StringBuilder();
            String type = null;
            boolean started = false, rootSeen = false, overflow = false;
            String opaqueEnd = null;
            boolean style = false;
            String line;
            while ((line = readBoundedLine(reader)) != null) {
                boolean tooLong = line.length() > LIMIT;
                String trimmed = line.stripLeading();
                if (trimmed.startsWith("\uFEFF")) trimmed = trimmed.substring(1);
                // 同时接受根节点与首个事件写在同一行的紧凑格式
                if (!started && trimmed.startsWith("<i>")) {
                    rootSeen = true;
                    header.append("<i>\n");
                    line = trimmed.substring(3);
                    trimmed = line.stripLeading();
                    if (trimmed.isEmpty()) continue;
                }
                if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<!ENTITY")) {
                    throw new IOException("不支持包含 DTD 或自定义实体声明的文件");
                }
                if (trimmed.matches("<i(?:\\s[^>]*)?>.*")) rootSeen = true;
                boolean opaque = opaqueEnd != null || style || trimmed.startsWith("<!--") || trimmed.startsWith("<![CDATA[");
                if (trimmed.contains("<BililiveRecorderXmlStyle")) style = true;
                if (opaqueEnd == null && trimmed.startsWith("<!--")) opaqueEnd = "-->";
                if (opaqueEnd == null && trimmed.startsWith("<![CDATA[")) opaqueEnd = "]]>";
                if (opaqueEnd != null && line.contains(opaqueEnd)) opaqueEnd = null;
                if (line.contains("</BililiveRecorderXmlStyle>")) style = false;
                var match = START.matcher(line);
                if (!opaque && match.find()) {
                    if (!rootSeen) throw new IOException("未找到明确的 <i> 根节点，不猜测文件类型");
                    if (!started) { writeHeader(header.toString(), writer); started = true; }
                    if (type != null) emit(type, record.toString(), overflow, writer);
                    type = match.group(1);
                    record.setLength(0);
                    overflow = tooLong;
                    record.append(line);
                } else if (!started) {
                    if (header.length() + line.length() < LIMIT) header.append(line).append('\n');
                    else throw new IOException("XML 文件头超过安全处理上限");
                } else if (!opaque && trimmed.equals("</i>")) {
                    if (type != null) emit(type, record.toString(), overflow, writer);
                    type = null;
                    record.setLength(0);
                } else if (type != null) {
                    if (record.length() + line.length() < LIMIT && !tooLong) record.append('\n').append(line);
                    else overflow = true;
                } else if (!trimmed.isEmpty()) unknownFragments++;
                if (type != null && !overflow) {
                    String complete = record.toString().strip();
                    if (complete.endsWith("</i>")) complete = complete.substring(0, complete.length() - 4).stripTrailing();
                    if (element(complete) != null) {
                        emit(type, record.toString(), false, writer);
                        type = null;
                        record.setLength(0);
                    }
                }
            }
            if (type != null) emit(type, record.toString(), overflow, writer);
            if (!started) throw new IOException("未找到逐行存储的录播姬事件，无法安全恢复");
            writer.write("\n</i>\n");
        }
    }

    // 处理超长行时，不分配与损坏输入规模成正比的内存
    private static String readBoundedLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        boolean read = false;
        while ((c = reader.read()) != -1) {
            read = true;
            if (c == '\n') break;
            if (line.length() <= LIMIT) line.append((char) c);
        }
        return read ? line.toString() : null;
    }

    private void writeHeader(String header, Writer writer) throws Exception {
        String clean = cleanCharacters(header).replaceFirst("^\\uFEFF", "");
        if (validDocument(clean + "</i>")) { writer.write(clean); return; }
        headerRebuilt = true;
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<i>\n");
        for (String line : clean.split("\\R")) {
            if (META.matcher(line).find() && element(line) != null) writer.write(line + "\n");
        }
    }

    private void emit(String type, String original, boolean overflow, Writer writer) throws Exception {
        if (overflow) { drop(type, "记录超过 4 MiB，边界无法可靠确认"); return; }
        String value = original.strip();
        if (value.endsWith("</i>")) value = value.substring(0, value.length() - 4).stripTrailing();
        Element intact = element(value);
        if (intact != null && intact.getTagName().equals(type)) {
            kept.merge(type, 1, Integer::sum);
            writer.write(value + "\n");
            return;
        }
        String cleaned = cleanCharacters(value);
        String candidate = element(cleaned) != null ? cleaned : escapeBareAmpersands(cleaned);
        Element repaired = element(candidate);
        if (repaired == null) {
            candidate = restoreFromRaw(type, candidate);
            repaired = candidate == null ? null : element(candidate);
        }
        if (repaired != null && type.equals(repaired.getTagName()) && validCore(type, repaired)) {
            recovered.merge(type, 1, Integer::sum);
            writer.write(candidate + "\n");
        } else drop(type, "结构或关键字段无法确认，未猜测用户、时间、金额或正文");
    }

    private String restoreFromRaw(String type, String candidate) {
        int end = openingEnd(candidate);
        if (end < 0) return null;
        String opening = candidate.substring(0, end + 1);
        if (opening.endsWith("/>")) return null;
        Element e = element(opening + "</" + type + ">");
        if (e == null || !validCore(type, e) || e.getAttribute("raw").isBlank()) return null;
        try {
            var raw = json.readTree(e.getAttribute("raw"));
            String body = candidate.substring(end + 1).stripTrailing();
            if (type.equals("gift") || type.equals("guard")) {
                // 这些记录没有正文；必须等原始数据完整后，才能恢复终止符
                if (!raw.isObject() || !body.isBlank()) return null;
                if (!raw.has("uid") || !raw.get("uid").asText().equals(e.getAttribute("uid"))) return null;
                return opening + "</" + type + ">";
            }
            String text = null;
            if (type.equals("d") && raw.isArray() && raw.size() > 2 && raw.get(1).isTextual()) {
                String[] p = e.getAttribute("p").split(",", -1);
                if (!raw.get(2).isArray() || raw.get(2).isEmpty() || !raw.get(2).get(0).asText().equals(p[6])) return null;
                text = raw.get(1).textValue();
            }
            if (type.equals("sc") && raw.isObject() && raw.has("message") && raw.get("message").isTextual()) {
                if (!raw.has("price") || new BigDecimal(raw.get("price").asText()).compareTo(new BigDecimal(e.getAttribute("price"))) != 0) return null;
                if (raw.has("uid") && !raw.get("uid").asText().equals(e.getAttribute("uid"))) return null;
                text = raw.get("message").textValue();
            }
            if (text == null) return null;
            String encoded = escapeText(text);
            // 只扩展匹配的前缀；绝不覆盖冲突内容
            if (!encoded.startsWith(body)) return null;
            return opening + encoded + "</" + type + ">";
        } catch (Exception ignored) { return null; }
    }

    private boolean validCore(String type, Element e) {
        try {
            if (type.equals("d")) {
                String[] p = e.getAttribute("p").split(",", -1);
                return p.length >= 8 && decimal(p[0], false) && integer(p[1], false) && integer(p[2], false)
                        && integer(p[3], false) && integer(p[4], false) && integer(p[6], false);
            }
            if (!decimal(e.getAttribute("ts"), false) || !integer(e.getAttribute("uid"), true)) return false;
            return switch (type) {
                case "gift" -> !e.getAttribute("giftname").isBlank() && integer(e.getAttribute("giftcount"), true);
                case "sc" -> decimal(e.getAttribute("price"), true) && decimal(e.getAttribute("time"), false);
                case "guard" -> Set.of("1", "2", "3").contains(e.getAttribute("level")) && integer(e.getAttribute("count"), true);
                default -> false;
            };
        } catch (Exception ignored) { return false; }
    }

    private static boolean decimal(String text, boolean positive) {
        int sign = new BigDecimal(text).signum();
        return positive ? sign > 0 : sign >= 0;
    }

    private static boolean integer(String text, boolean positive) {
        return text.matches("[0-9]{1,19}") && (positive ? Long.parseLong(text) > 0 : Long.parseLong(text) >= 0);
    }

    private Element element(String xml) {
        try {
            var root = parser.parse(new InputSource(new StringReader("<i>" + xml + "</i>"))).getDocumentElement();
            Element result = null;
            for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element e) { if (result != null) return null; result = e; }
                else if (node.getNodeType() != org.w3c.dom.Node.TEXT_NODE || !node.getTextContent().isBlank()) return null;
            }
            if (result == null) return null;
            for (var node = result.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element) return null;
            }
            return result;
        } catch (Exception ignored) { return null; }
    }

    private boolean validDocument(String xml) {
        try { return "i".equals(parser.parse(new InputSource(new StringReader(xml))).getDocumentElement().getTagName()); }
        catch (Exception ignored) { return false; }
    }

    private static int openingEnd(String value) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i;
        }
        return -1;
    }

    private static String cleanCharacters(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF
                || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String escapeBareAmpersands(String value) {
        // 未知命名实体存在歧义，因此有意将其保留为错误
        var opaque = Pattern.compile("(?s)<!\\[CDATA\\[.*?]]>|<!--.*?-->").matcher(value);
        StringBuilder result = new StringBuilder();
        int from = 0;
        while (opaque.find()) {
            result.append(escapeAmpSegment(value.substring(from, opaque.start()))).append(opaque.group());
            from = opaque.end();
        }
        return result.append(escapeAmpSegment(value.substring(from))).toString();
    }

    private static String escapeAmpSegment(String value) {
        return value.replaceAll("&(?!(?:[A-Za-z_:][A-Za-z0-9_.:-]*|#[0-9]+|#x[0-9A-Fa-f]+);)", "&amp;");
    }

    private static String escapeText(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private void drop(String type, String reason) { discarded.merge(type, 1, Integer::sum); reasons.merge(type + "：" + reason, 1, Integer::sum); }
    private int total(String type) { return kept.get(type) + recovered.get(type); }
    private static Map<String, Integer> counters() {
        Map<String, Integer> values = new LinkedHashMap<>();
        TYPES.forEach(type -> values.put(type, 0));
        return values;
    }
}
