package dev.moduled.patcher.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Элемент бинарного XML: имя, атрибуты и дочерние элементы. */
public final class XmlElement {

    public final String name;
    public final Map<String, XmlValue> attributes;
    public final List<XmlElement> children = new ArrayList<>();
    public XmlElement parent;

    public XmlElement(String name, Map<String, XmlValue> attributes) {
        this.name = name;
        this.attributes = Collections.unmodifiableMap(
                attributes != null ? new LinkedHashMap<>(attributes) : new LinkedHashMap<>());
    }

    public XmlValue attribute(String name) {
        return attributes.get(name);
    }

    public String stringAttribute(String name) {
        XmlValue value = attributes.get(name);
        return value != null ? value.asString() : null;
    }

    public Integer intAttribute(String name) {
        XmlValue value = attributes.get(name);
        return value != null ? value.asInteger() : null;
    }

    public List<XmlElement> childrenNamed(String childName) {
        List<XmlElement> found = new ArrayList<>();
        for (XmlElement child : children) {
            if (child.name.equals(childName)) {
                found.add(child);
            }
        }
        return found;
    }

    public XmlElement firstChild(String childName) {
        for (XmlElement child : children) {
            if (child.name.equals(childName)) {
                return child;
            }
        }
        return null;
    }

    /** Обход в глубину: нужен для поиска provider/activity/uses-permission. */
    public List<XmlElement> descendantsNamed(String descendantName) {
        List<XmlElement> found = new ArrayList<>();
        collect(descendantName, found);
        return found;
    }

    private void collect(String wanted, List<XmlElement> out) {
        for (XmlElement child : children) {
            if (child.name.equals(wanted)) {
                out.add(child);
            }
            child.collect(wanted, out);
        }
    }

    @Override
    public String toString() {
        return "<" + name + " " + attributes + " children=" + children.size() + '>';
    }
}
