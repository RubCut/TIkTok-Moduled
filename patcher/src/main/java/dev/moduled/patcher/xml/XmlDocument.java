package dev.moduled.patcher.xml;

import java.util.ArrayList;
import java.util.List;

/** Документ бинарного XML Android: корневой элемент и объявления пространств имён. */
public final class XmlDocument {

    public final XmlElement root;
    public final List<String[]> namespaces = new ArrayList<>();

    public XmlDocument(XmlElement root) {
        this.root = root;
    }

    /** Представление для отчёта патчера: структура без лишних деталей. */
    public String toStructureText(int maxDepth) {
        StringBuilder sb = new StringBuilder();
        append(sb, root, 0, maxDepth);
        return sb.toString();
    }

    private void append(StringBuilder sb, XmlElement element, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return;
        }
        sb.append("  ".repeat(depth)).append('<').append(element.name);
        element.attributes.forEach((name, value) ->
                sb.append(' ').append(name).append("=\"").append(value.asString()).append('"'));
        sb.append(">\n");
        for (XmlElement child : element.children) {
            append(sb, child, depth + 1, maxDepth);
        }
    }
}
