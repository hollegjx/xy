package com.xy.generation.service.impl;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.parser.Tag;
import org.jsoup.parser.TagSet;

/** 只检查本轮删除的组件与最终源码之间的字面量引用，不执行生成代码。 */
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
    private static final Set<String> VOID_ELEMENTS = Set.of("area", "base", "br", "col", "embed", "hr",
            "img", "input", "link", "meta", "param", "source", "track", "wbr");

    static String findError(Map<String, String> sources, Set<String> deleted) {
        var missing = new HashSet<>(deleted);
        missing.removeAll(sources.keySet()); // 同一轮也可以恢复同一路径。
        if (missing.isEmpty()) return null;
        for (var file : sources.entrySet()) {
            String path = file.getKey();
            String target = null;
            if (path.endsWith(".js")) {
                target = importedTarget(path, file.getValue(), missing);
            } else if (path.endsWith(".vue")) {
                for (var block : parseVueBlocks(file.getValue()).children()) {
                    if (block.hasAttr("src")) target = resolve(path, block.attr("src"), missing);
                    if (target == null && block.normalName().equals("script"))
                        target = importedTarget(path, block.data(), missing);
                    if (target == null && block.normalName().equals("template"))
                        target = templateTarget(path, block, missing);
                    if (target != null) break;
                }
            }
            if (target != null) return path + " 仍引用已删除组件 " + target + "，请清理引用后重试";
        }
        return null;
    }

    private static Element parseVueBlocks(String source) {
        // SFC 按源码中的 /> 闭合元素，不经过浏览器 HTML 的纠错和补标签。
        var tags = new TagSet().onNewTag(tag -> {
            String name = tag.name();
            if (VOID_ELEMENTS.contains(name)) tag.set(Tag.Void);
            else if (name.equals("script") || name.equals("style")) tag.set(Tag.Data);
            else if (name.equals("textarea") || name.equals("title")) tag.set(Tag.RcData);
        });
        return Parser.xmlParser().tagSet(tags).parseInput(source, "");
    }

    private static String templateTarget(String path, Element template, Set<String> missing) {
        for (var element : template.getAllElements()) {
            if (element.closest("[v-pre]") != null) continue;
            for (var attribute : element.attributes()) {
                String key = attribute.getKey();
                if (key.startsWith("v-") || key.startsWith(":") || key.startsWith("@")
                        || key.startsWith("#") || key.startsWith(".")) {
                    String target = importedTarget(path, attribute.getValue(), missing);
                    if (target != null) return target;
                }
            }
            for (var text : element.textNodes()) {
                var expressions = INTERPOLATION.matcher(text.getWholeText());
                while (expressions.find()) {
                    String target = importedTarget(path, expressions.group(1), missing);
                    if (target != null) return target;
                }
            }
        }
        return null;
    }

    private static String importedTarget(String path, String script, Set<String> missing) {
        String previous = "", beforePrevious = "", pending = null;
        var tokens = JS_TOKEN.matcher(script);
        while (tokens.find()) {
            String token = tokens.group();
            if (token.startsWith("//") || token.startsWith("/*")) continue;
            if (token.equals("/") && EXPRESSION_PREFIX.contains(previous)) {
                var regexp = REGEXP.matcher(script).region(tokens.start(), script.length());
                if (regexp.lookingAt()) {
                    tokens.region(regexp.end(), script.length());
                    token = "<regexp>";
                }
            }
            if (pending != null && (token.equals(")") || token.equals(","))) return pending;
            pending = null;
            char first = token.charAt(0);
            if (token.length() >= 2 && (first == '\'' || first == '"' || first == '`')) {
                String specifier = token.substring(1, token.length() - 1);
                if (previous.equals("from") || previous.equals("import")) {
                    String target = resolve(path, specifier, missing);
                    if (target != null) return target;
                } else if (previous.equals("(") && (beforePrevious.equals("import") || beforePrevious.equals("require"))) {
                    pending = resolve(path, specifier, missing);
                }
            }
            beforePrevious = previous;
            previous = token;
        }
        return null;
    }

    private static String resolve(String importer, String specifier, Set<String> missing) {
        String name = specifier.split("[?#]", 2)[0];
        if (!name.endsWith(".vue") || name.indexOf('\0') >= 0) return null;
        Path resolved;
        if (name.startsWith("/src/")) resolved = Path.of(name.substring(1));
        else if (name.startsWith("./") || name.startsWith("../")) resolved = Path.of(importer).resolveSibling(name);
        else return null;
        String target = resolved.normalize().toString().replace('\\', '/');
        return missing.contains(target) ? target : null;
    }

    private VueComponentReferences() {}
}
