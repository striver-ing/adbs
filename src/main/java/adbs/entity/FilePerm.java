package adbs.entity;

public class FilePerm {

    public final boolean readable;

    public final boolean writable;

    public final boolean executable;

    public FilePerm(boolean readable, boolean writable, boolean executable) {
        this.readable = readable;
        this.writable = writable;
        this.executable = executable;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(readable ? "r" : "-");
        sb.append(writable ? "w" : "-");
        sb.append(executable ? "x" : "-");
        return sb.toString();
    }
}
