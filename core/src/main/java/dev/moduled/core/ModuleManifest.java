package dev.moduled.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Внутренний manifest модуля (ТЗ §10.1). Неизменяемый снимок проверенных полей.
 */
public final class ModuleManifest {
    public final String id;
    public final String name;
    public final String description;
    public final String version;
    public final long versionCode;
    public final int apiVersion;
    public final String entrypoint;
    public final List<String> targetPackages;

    public ModuleManifest(String id, String name, String description, String version,
                          long versionCode, int apiVersion, String entrypoint,
                          List<String> targetPackages) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.version = version;
        this.versionCode = versionCode;
        this.apiVersion = apiVersion;
        this.entrypoint = entrypoint;
        this.targetPackages = Collections.unmodifiableList(new ArrayList<>(targetPackages));
    }

    public boolean targetsPackage(String packageName) {
        return targetPackages.contains(packageName);
    }

    @Override
    public String toString() {
        return id + " v" + version + " (" + versionCode + ") api=" + apiVersion;
    }
}
