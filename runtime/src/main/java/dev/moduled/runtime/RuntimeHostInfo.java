package dev.moduled.runtime;

import dev.moduled.api.HostInfo;

/** Неизменяемые сведения о хосте и адаптере (ТЗ §9, PAT-08/PAT-09). */
public final class RuntimeHostInfo implements HostInfo {

    private final String packageName;
    private final String versionName;
    private final long versionCode;
    private final String adapterName;
    private final String adapterVersion;
    private final boolean adapterSupported;
    private final String[] primaryAbis;

    public RuntimeHostInfo(String packageName, String versionName, long versionCode,
                           String adapterName, String adapterVersion, boolean adapterSupported,
                           String[] primaryAbis) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.adapterName = adapterName;
        this.adapterVersion = adapterVersion;
        this.adapterSupported = adapterSupported;
        this.primaryAbis = primaryAbis != null ? primaryAbis.clone() : new String[0];
    }

    @Override
    public String packageName() {
        return packageName;
    }

    @Override
    public String versionName() {
        return versionName;
    }

    @Override
    public long versionCode() {
        return versionCode;
    }

    @Override
    public String adapterName() {
        return adapterName;
    }

    @Override
    public String adapterVersion() {
        return adapterVersion;
    }

    @Override
    public boolean adapterSupported() {
        return adapterSupported;
    }

    @Override
    public String[] primaryAbis() {
        return primaryAbis.clone();
    }
}
