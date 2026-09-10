package dtm.ide.deps;

public record DependencyVersionChoice(String version, boolean local, boolean remote) {

    public DependencyVersionChoice {
        version = version == null ? "" : version.trim();
    }

    public boolean localOnly() {
        return local && !remote;
    }

    @Override
    public String toString() {
        return version;
    }
}
