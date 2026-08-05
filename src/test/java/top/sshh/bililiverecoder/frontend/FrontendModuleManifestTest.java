package top.sshh.bililiverecoder.frontend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendModuleManifestTest {

    private static final String MANIFEST = "static/modules/manifest.json";
    private static final Set<String> EXPECTED_PAGES = Set.of("room", "user", "history", "stats", "log");
    private static final Set<String> EXPECTED_SHELL_MODULES = Set.of("system-settings", "notification-settings");
    private static final Pattern FULL_DOCUMENT_TAG = Pattern.compile("<\\s*(html|head|body)(\\s|>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORTAL_COMPONENT = Pattern.compile(
            "<el-(dialog|drawer|select|date-picker|time-picker|tooltip|popover|dropdown-menu|autocomplete|cascader|color-picker)(?=\\s|>)(?:\"[^\"]*\"|'[^']*'|[^>])*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CUSTOM_CLASS_ATTRIBUTE = Pattern.compile(
            "(?:^|\\s):?custom-class\\s*=",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POPPER_CLASS_ATTRIBUTE = Pattern.compile(
            "(?:^|\\s)popper-class\\s*=",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLASS_ATTRIBUTE = Pattern.compile(
            "(?:^|\\s):?class\\s*=",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_POPPER_ATTRIBUTE = Pattern.compile(
            "(?:^|\\s):?popper-append-to-body\\s*=\\s*['\"]false['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_ELEMENT_SERVICE = Pattern.compile(
            "\\$(confirm|alert|prompt|msgbox|loading)\\s*\\(");
    private static final Pattern TEMPLATE_TAG = Pattern.compile(
            "<!--.*?-->|<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9-]*)(?:\"[^\"]*\"|'[^']*'|[^>])*>",
            Pattern.DOTALL);
    private static final Set<String> VOID_HTML_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void manifestDeclaresCompleteLoadableModules() throws IOException {
        JsonNode manifest = readJson(MANIFEST);
        assertEquals(1, manifest.path("version").asInt());
        JsonNode pages = manifest.path("pages");
        assertTrue(pages.isObject());

        Set<String> names = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = pages.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            names.add(field.getKey());
            assertModule(field.getKey(), field.getValue(), true);
        }
        assertEquals(EXPECTED_PAGES, names);

        JsonNode shell = manifest.path("shell");
        assertTrue(shell.isObject());
        names.clear();
        fields = shell.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            names.add(field.getKey());
            assertModule(field.getKey(), field.getValue(), false);
        }
        assertEquals(EXPECTED_SHELL_MODULES, names);
    }

    @Test
    void runtimeAndNativeConfigurationCoverDynamicAssets() throws IOException {
        String loader = readText("static/js/app/module-loader.js");
        assertTrue(loader.contains("fetch(withBuildId(path)"));
        assertTrue(loader.contains("link.href = withBuildId(path)"));
        assertTrue(loader.contains("script.src = withBuildId(path)"));
        assertTrue(loader.contains("delete modulePromises[moduleKey]"));
        assertTrue(loader.contains("loadShellModule"));
        assertTrue(loader.contains("hasUnsafeDecodedPath"));
        assertTrue(loader.contains("BiliupUrlResolver"));
        assertTrue(loader.contains("activatePageStyles"));
        assertTrue(loader.contains("moduleFragments"));
        assertTrue(loader.contains("composeTemplate"));
        assertTrue(loader.indexOf("loadScript(config.entry)")
                > loader.indexOf("Promise.all([templatePromise, fragmentPromise, stylePromise, dependencyPromise])"));

        JsonNode nativeConfig = readJson("META-INF/native-image/resource-config.json");
        List<Pattern> nativePatterns = new ArrayList<>();
        for (JsonNode include : nativeConfig.path("resources").path("includes")) {
            String expression = include.path("pattern").asText();
            nativePatterns.add(Pattern.compile(expression));
            if (expression.startsWith("\\Qstatic/") && expression.endsWith("\\E")) {
                String exactResource = expression.substring(2, expression.length() - 2);
                assertNotNull(getClass().getClassLoader().getResource(exactResource), exactResource);
            }
        }

        Set<String> dynamicResources = new HashSet<>();
        collectDynamicResources(readJson(MANIFEST), dynamicResources);
        dynamicResources.add("/modules/manifest.json");
        for (String resource : dynamicResources) {
            assertNativeCovered(nativePatterns, toClasspathResource(resource));
        }

        for (String resource : new String[]{
                "static/js/app/module-loader.js",
                "static/js/app/module-registry.js",
                "static/js/app/url-resolver.js",
                "static/js/app/page-state-coordinator.js",
                "static/js/app/page-portal-services.js",
                "static/js/components/page-host.js",
                "static/js/components/shell-module-host.js",
                "static/js/components/notification-channel-fields.js",
                "static/js/app/shell/system-settings.js",
                "static/js/app/shell/storage-settings.js",
                "static/js/app/shell/notifications.js",
                "static/js/api/storage-api.js",
                "static/js/api/notification-api.js"
        }) {
            assertNativeCovered(nativePatterns, resource);
        }
    }

    @Test
    void shellDoesNotEagerLoadBusinessPagesOrKeepIframeTemplates() throws IOException {
        for (String resource : new String[]{"static/index.html", "static/mobile/index.html"}) {
            String html = readText(resource);
            assertPortalClasses(html, resource);
            assertFalse(html.contains("text/x-template\" id=\"user-template"));
            assertFalse(html.contains("text/x-template\" id=\"log-template"));
            assertFalse(html.contains("js/pages/user.js"));
            assertFalse(html.contains("js/pages/log.js"));
            assertFalse(html.contains("js/echarts.min.js"));
            assertFalse(html.contains("global-preview-player.js"));
            assertFalse(html.contains("api/log-api.js"));
            assertFalse(html.contains("tab-frame"));
            assertTrue(html.contains("biliup-page-host"));
            assertTrue(html.contains("biliup-shell-module-host"));
            assertTrue(html.contains("page-portal-services.js"));
            assertTrue(html.contains("url-resolver.js"));
            assertTrue(html.indexOf("url-resolver.js") < html.indexOf("api.js"));
            assertFalse(html.contains("class=\"config-panel\""));
            assertFalse(html.contains("notificationRuleEditor"));
            assertFalse(html.contains("api/storage-api.js"));
            assertFalse(html.contains("api/notification-api.js"));
            assertFalse(html.contains("shell/system-settings.js"));
            assertFalse(html.contains("shell/notifications.js"));
        }
        assertTrue(readText("static/modules/shell/system-settings/mobile.html")
                .contains("v-show=\"configExpanded\""));
    }

    @Test
    void moduleTemplatesAndScriptsUseSamePageContracts() throws IOException {
        JsonNode pages = readJson(MANIFEST).path("pages");
        Iterator<Map.Entry<String, JsonNode>> fields = pages.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String pageName = field.getKey();
            JsonNode module = field.getValue();
            StringBuilder scripts = new StringBuilder();
            module.path("scripts").forEach(resource -> appendResource(scripts, resource.asText()));
            appendResource(scripts, module.path("entry").asText());

            String source = scripts.toString();
            assertFalse(source.contains("postMessage"), pageName);
            assertFalse(source.contains("window.parent"), pageName);
            assertFalse(source.contains("parent.answer"), pageName);
            assertFalse(RAW_ELEMENT_SERVICE.matcher(source).find(), pageName + " uses raw Element service");
            assertTrue(source.contains("page-ready"), pageName);
            assertTrue(source.contains("connection-status"), pageName);
            assertTrue(source.contains("page-state"), pageName);
            assertTrue(readText(toClasspathResource(module.path("entry").asText())).contains("beforeDestroy"), pageName);
            if ("log".equals(pageName)) {
                assertTrue(source.contains("diagnostic-export', { history: {} }"), pageName);
            }
        }
    }

    @Test
    void moduleScrollRootsUseTheRealHostSizedScroller() throws IOException {
        String historyDesktop = readText("static/modules/pages/history/desktop.html");
        assertFalse(historyDesktop.substring(0, historyDesktop.indexOf('>')).contains("data-page-scroll-root"));
        assertTrue(historyDesktop.contains("class=\"history-main\" data-page-scroll-root"));

        String mobilePages = readText("static/mobile/css/mobile-pages.css");
        assertTrue(mobilePages.contains(".stats-container {\n    height: 100%;\n    min-height: 0;"));
        for (String page : new String[]{"stats", "history", "room"}) {
            String mobileCss = readText("static/modules/pages/" + page + "/mobile.css");
            assertTrue(mobileCss.contains("height: 100%;"), page);
            assertTrue(mobileCss.contains("min-height: 0;"), page);
            assertFalse(mobileCss.substring(0, Math.min(mobileCss.length(), 900))
                    .contains("height: var(--mobile-page-viewport-height);"), page);
        }
    }

    private void assertModule(String moduleName, JsonNode module, boolean requiresScrollRoot) throws IOException {
        assertEquals("module", module.path("mode").asText(), moduleName);
        assertFalse(module.path("module").asText().isBlank(), moduleName);
        assertFalse(module.path("component").asText().isBlank(), moduleName);

        JsonNode templates = module.path("templates");
        for (String surface : new String[]{"desktop", "mobile"}) {
            String path = templates.path(surface).asText();
            assertDynamicResource(path, moduleName + " " + surface + " template");
            String template = readText(toClasspathResource(path));
            assertFalse(FULL_DOCUMENT_TAG.matcher(template).find(), path);
            assertSingleTemplateRoot(template, path);
            assertPortalClasses(template, path);
            if (requiresScrollRoot) {
                assertTrue(template.contains("data-page-scroll-root"), path);
            }
        }

        JsonNode styles = module.path("styles");
        assertResourceArray(styles.path("common"), moduleName + " common styles");
        assertResourceArray(styles.path("desktop"), moduleName + " desktop styles");
        assertResourceArray(styles.path("mobile"), moduleName + " mobile styles");
        assertResourceArray(module.path("scripts"), moduleName + " scripts");
        JsonNode fragments = module.path("fragments");
        if (fragments.isObject()) {
            assertFragmentResources(fragments, moduleName + " fragments");
        }
        assertDynamicResource(module.path("entry").asText(), moduleName + " entry");
    }

    private void assertFragmentResources(JsonNode fragments, String label) throws IOException {
        Iterator<Map.Entry<String, JsonNode>> fields = fragments.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fragmentLabel = label + " " + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                assertFragmentResources(value, fragmentLabel);
                continue;
            }
            assertTrue(value.isTextual(), fragmentLabel);
            String path = value.asText();
            assertDynamicResource(path, fragmentLabel);
            String template = readText(toClasspathResource(path));
            assertFalse(FULL_DOCUMENT_TAG.matcher(template).find(), path);
            assertPortalClasses(template, path);
        }
    }

    private void assertPortalClasses(String template, String path) {
        Matcher matcher = PORTAL_COMPONENT.matcher(template);
        while (matcher.find()) {
            String kind = matcher.group(1).toLowerCase();
            String tag = matcher.group();
            if ("dropdown-menu".equals(kind)) {
                assertTrue(CLASS_ATTRIBUTE.matcher(tag).find(), path + " dropdown portal: " + tag);
            } else if (!"dialog".equals(kind) && !"drawer".equals(kind)) {
                assertTrue(POPPER_CLASS_ATTRIBUTE.matcher(tag).find()
                                || INLINE_POPPER_ATTRIBUTE.matcher(tag).find(),
                        path + " " + kind + " portal: " + tag);
            } else {
                assertTrue(CUSTOM_CLASS_ATTRIBUTE.matcher(tag).find(),
                        path + " " + kind + " portal: " + tag);
            }
        }
    }

    private void assertSingleTemplateRoot(String template, String path) {
        Deque<String> stack = new ArrayDeque<>();
        int roots = 0;
        Matcher matcher = TEMPLATE_TAG.matcher(template);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("<!--")) {
                continue;
            }
            String name = matcher.group(2).toLowerCase();
            boolean closing = !matcher.group(1).isEmpty();
            if (closing) {
                assertFalse(stack.isEmpty(), path + " unexpected closing tag: " + name);
                assertEquals(stack.pop(), name, path + " mismatched closing tag: " + name);
                continue;
            }
            if (stack.isEmpty()) {
                roots++;
            }
            boolean selfClosing = token.matches("(?s).*/\\s*>") || VOID_HTML_TAGS.contains(name);
            if (!selfClosing) {
                stack.push(name);
            }
        }
        assertTrue(stack.isEmpty(), path + " unclosed tag: " + (stack.isEmpty() ? "" : stack.peek()));
        assertEquals(1, roots, path + " must have exactly one valid root element");
    }

    private void appendResource(StringBuilder target, String absolutePath) {
        try {
            target.append(readText(toClasspathResource(absolutePath))).append('\n');
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + absolutePath, e);
        }
    }

    private void assertResourceArray(JsonNode resources, String label) {
        assertTrue(resources.isArray(), label);
        resources.forEach(resource -> assertDynamicResource(resource.asText(), label));
    }

    private void assertDynamicResource(String path, String label) {
        assertFalse(path.isBlank(), label);
        assertTrue(path.startsWith("/"), label + ": " + path);
        assertFalse(path.startsWith("//"), label + ": " + path);
        assertFalse(path.contains("\\"), label + ": " + path);
        String decoded = path;
        while (true) {
            String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        for (String segment : decoded.split("/")) {
            assertFalse("..".equals(segment), label + ": " + path);
        }
        assertNotNull(getClass().getClassLoader().getResource(toClasspathResource(path)), label + ": " + path);
    }

    private void collectDynamicResources(JsonNode node, Set<String> resources) {
        if (node.isTextual() && node.asText().startsWith("/")) {
            resources.add(node.asText());
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectDynamicResources(child, resources));
        }
    }

    private void assertNativeCovered(List<Pattern> patterns, String resource) {
        assertTrue(patterns.stream().anyMatch(pattern -> pattern.matcher(resource).matches()),
                "native-image resource missing: " + resource);
    }

    private JsonNode readJson(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return objectMapper.readTree(input);
        }
    }

    private String readText(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String toClasspathResource(String absolutePath) {
        return "static" + absolutePath;
    }
}
