package adbs.entity;

import adbs.constant.FileType;

public class StatMode {

    public final FileType type;

    public final FilePerm owner;

    public final FilePerm group;

    public final FilePerm other;

    public StatMode(FileType type, FilePerm owner, FilePerm group, FilePerm other) {
        this.type = type;
        this.owner = owner;
        this.group = group;
        this.other = other;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.code());
        sb.append(owner.toString());
        sb.append(group.toString());
        sb.append(other.toString());
        return sb.toString();
    }
}
