package com.tsi.launcher.data;

public enum VersionFilter {
    ALL("All versions"),
    RELEASE("Release"),
    SNAPSHOT("Snapshot"),
    OLD_ALPHA("Old Alpha"),
    OLD_BETA("Old Beta");

    private final String label;
    VersionFilter(String label) { this.label = label; }
    public String getLabel() { return label; }

    public boolean accepts(String type) {
        switch (this) {
            case ALL: return true;
            case RELEASE: return "release".equalsIgnoreCase(type);
            case SNAPSHOT: return "snapshot".equalsIgnoreCase(type);
            case OLD_ALPHA: return "old_alpha".equalsIgnoreCase(type);
            case OLD_BETA: return "old_beta".equalsIgnoreCase(type);
            default: return false;
        }
    }
}
