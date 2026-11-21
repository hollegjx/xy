package com.xy.generation.service.impl;

import com.xy.manager.GeneratedFileManager;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.parser.Tag;
import org.jsoup.parser.TagSet;

/** 从最终 Vue / JS 源码识别直接的本地模块引用，不执行生成代码。 */
final class VueComponentReferences {
    private static final Pattern JS_TOKEN = Pattern.compile(
            "//[^\\r\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z)|'(?:[^'\\\\]++|\\\\[\\s\\S])*+'"
            + "|\"(?:[^\"\\\\]++|\\\\[\\s\\S])*+\"|`(?:[^`\\\\]++|\\\\[\\s\\S])*+`|[\\w$]+|\\S");
    // 转义符和字符组中的 / 都不是正则结束符。
    private static final Pattern REGEXP = Pattern.compile(
            "/(?:\\\\[^\\r\\n]|\\[(?:\\\\[^\\r\\n]|[^\\]\\\\\\r\\n])*+\\]|[^/\\[\\\\\\r\\n]++)++/[a-zA-Z]*");
    private static final Set<String> EXPRESSION_PREFIX = Set.of("", "=", "(", "[", "{", ",", ":", ";", "!",
            "?", "~", "&", "|", "^", "+", "-", "*", "%", "/", "<", ">", "return", "throw", "case", "yield",
            "await", "typeof", "void", "delete", "in", "instanceof");
    private static final Pattern INTERPOLATION = Pattern.compile("\\{\\{(.*?)}}", Pattern.DOTALL);
    private static final Pattern DIAGRAM_COMPONENT = Pattern.compile("src/components/XyArchitecture(?:[2-9]|[1-9][0-9]+)?\\.vue");
    private static final Set<String> VOID_ELEMENTS = Set.of("area", "base", "br", "col", "embed", "hr",
            "img", "input", "link", "meta", "param", "source", "track", "wbr");

    static String findError(Map<String, String> sources, Set<String> deleted) {
        var missing = new HashSet<>(deleted);
        missing.removeAll(sources.keySet()); // 同一轮也可以恢复同一路径。
        if (missing.isEmpty()) return null;
        var ref = firstReference(sources, missing::contains);
        return ref == null ? null : ref.importer() + " 仍引用已删除组件 " + ref.target() + "，请清理引用后重试";
    }

    static boolean references(Map<String, String> sources, String target) {
        return firstReference(sources, referencePaths(sources, target)::contains) != null;
    }

    static String missingDiagramComponent(Map<String, String> sources) {
        var ref = firstReference(sources, path -> !sources.containsKey(path) && DIAGRAM_COMPONENT.matcher(path).matches());
        return ref == null ? null : ref.target();
    }

    static String missingPhotoError(Map<String, String> sources, Set<String> saved) {
        var ref = firstReference(sources, path -> {
            String name = path.endsWith(".json") ? path : path + ".json";
            return GeneratedFileManager.isVuePhoto(name) && !saved.contains(name);
        });
        if (ref == null) return null;
        String name = ref.target().endsWith(".json") ? ref.target() : ref.target() + ".json";
        return ref.importer() + " 引用了未取得的照片 " + name + "，请使用已有照片或文字";
    }

    /** 旧版工具使用默认导入；区分图本身的数据和同一组件另外导入的标题等数据。 */
    static boolean readsDefaultImportFields(String importer, String script, Map<String, String> sources,
            String target, Set<String> fields) {
        return readsDefaultImportFields(importer, script, sources, target, fields, script);
    }

    static boolean readsDefaultImportFields(String importer, String script, Map<String, String> sources,
            String target, Set<String> fields, String expression) {
        var paths = referencePaths(sources, target);
        var bindings = new HashSet<String>();
        var tokens = JS_TOKEN.matcher(script);
        String third = "", second = "", previous = "";
        for (String token; (token = nextToken(script, tokens, previous)) != null; ) {
            if (third.equals("import") && previous.equals("from") && (token.startsWith("'") || token.startsWith("\""))
                    && token.length() >= 2 && resolve(importer, token.substring(1, token.length() - 1), paths::contains) != null) {
                bindings.add(second);
            }
            third = second;
            second = previous;
            previous = token;
        }
        if (bindings.isEmpty()) return false;
        var reads = new HashMap<String, Set<String>>();
        tokens = JS_TOKEN.matcher(expression);
        second = previous = "";
        for (String token; (token = nextToken(expression, tokens, previous)) != null; ) {
            if (previous.equals(".") && bindings.contains(second) && fields.contains(token)) {
                var found = reads.computeIfAbsent(second, ignored -> new HashSet<>());
                found.add(token);
                if (found.containsAll(fields)) return true;
            }
            second = previous;
            previous = token;
        }
        return false;
    }

    private static Set<String> referencePaths(Map<String, String> sources, String target) {
        if (target.endsWith(".json") && sources.containsKey(target)) {
            String stem = target.substring(0, target.length() - ".json".length());
            // 固定工程中，省略扩展名时 Vite 先解析同名 .js，再解析 .json。
            if (!sources.containsKey(stem + ".js")) return Set.of(target, stem);
        }
        return Set.of(target);
    }

    private record Reference(String importer, String target) {}

    private static Reference firstReference(Map<String, String> sources, Predicate<String> targets) {
        for (var file : sources.entrySet()) {
            String path = file.getKey();
            String target = null;
            if (path.endsWith(".js")) {
                target = importedTarget(path, file.getValue(), targets);
            } else if (path.endsWith(".vue")) {
                for (var block : parseVueBlocks(file.getValue()).children()) {
                    if (block.hasAttr("src")) target = resolve(path, block.attr("src"), targets);
                    if (target == null && block.normalName().equals("script"))
                        target = importedTarget(path, block.data(), targets);
                    if (target == null && block.normalName().equals("template"))
                        target = templateTarget(path, block, targets);
                    if (target != null) break;
                }
            }
            if (target != null) return new Reference(path, target);
        }
        return null;
    }

    static Element parseVueBlocks(String source) {
        // SFC 按源码中的 /> 闭合元素，不经过浏览器 HTML 的纠错和补标签。
        var tags = new TagSet().onNewTag(tag -> {
            String name = tag.name();
            if (VOID_ELEMENTS.contains(name)) tag.set(Tag.Void);
            else if (name.equals("script") || name.equals("style")) tag.set(Tag.Data);
            else if (name.equals("textarea") || name.equals("title")) tag.set(Tag.RcData);
        });
        return Parser.xmlParser().tagSet(tags).parseInput(source, "");
    }

    private static String templateTarget(String path, Element template, Predicate<String> targets) {
        for (var element : template.getAllElements()) {
            if (element.closest("[v-pre]") != null) continue;
            for (var attribute : element.attributes()) {
                String key = attribute.getKey();
                if (key.startsWith("v-") || key.startsWith(":") || key.startsWith("@")
                        || key.startsWith("#") || key.startsWith(".")) {
                    String target = importedTarget(path, attribute.getValue(), targets);
                    if (target != null) return target;
                }
            }
            for (var text : element.textNodes()) {
                var expressions = INTERPOLATION.matcher(text.getWholeText());
                while (expressions.find()) {
                    String target = importedTarget(path, expressions.group(1), targets);
                    if (target != null) return target;
                }
            }
        }
        return null;
    }

    private static String importedTarget(String path, String script, Predicate<String> targets) {
        String previous = "", beforePrevious = "", pending = null;
        var tokens = JS_TOKEN.matcher(script);
        for (String token; (token = nextToken(script, tokens, previous)) != null; ) {
            if (pending != null && (token.equals(")") || token.equals(","))) return pending;
            pending = null;
            char first = token.charAt(0);
            if (token.length() >= 2 && (first == '\'' || first == '"' || first == '`')) {
                String specifier = token.substring(1, token.length() - 1);
                if (previous.equals("from") || previous.equals("import")) {
                    String target = resolve(path, specifier, targets);
                    if (target != null) return target;
                } else if (previous.equals("(") && (beforePrevious.equals("import") || beforePrevious.equals("require"))) {
                    pending = resolve(path, specifier, targets);
                }
            }
            beforePrevious = previous;
            previous = token;
        }
        return null;
    }

    private static String nextToken(String script, Matcher tokens, String previous) {
        while (tokens.find()) {
            String token = tokens.group();
            if (token.startsWith("//") || token.startsWith("/*")) continue;
            if (token.equals("/") && EXPRESSION_PREFIX.contains(previous)) {
                var regexp = REGEXP.matcher(script).region(tokens.start(), script.length());
                if (regexp.lookingAt()) {
                    tokens.region(regexp.end(), script.length());
                    return "<regexp>";
                }
            }
            return token;
        }
        return null;
    }

    private static String resolve(String importer, String specifier, Predicate<String> targets) {
        String name = specifier.split("[?#]", 2)[0];
        if (name.indexOf('\0') >= 0) return null;
        Path resolved;
        if (name.startsWith("/") && !name.startsWith("//")) resolved = Path.of(name.substring(1));
        else if (name.startsWith("@/")) resolved = Path.of("src/" + name.substring(2));
        else if (name.startsWith("./") || name.startsWith("../")) resolved = Path.of(importer).resolveSibling(name);
        else return null;
        String target = resolved.normalize().toString().replace('\\', '/');
        return targets.test(target) ? target : null;
    }

    private VueComponentReferences() {}
}
