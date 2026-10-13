package dev.ide.applog.relay;

/** Endpoint must enforce BOTH application ID and this operation scope on the bearer token. */
public enum RelayScope {
    EVENTS("events"), BACKUP_UPLOAD("backup-upload"), BACKUP_DOWNLOAD("backup-download");
    private final String wire;
    RelayScope(String wire) { this.wire = wire; }
    public String wireName() { return wire; }
    static RelayScope fromWire(String wire) {
        for (RelayScope scope : values()) if (scope.wire.equals(wire)) return scope;
        throw new IllegalArgumentException("Unknown relay scope");
    }
}
