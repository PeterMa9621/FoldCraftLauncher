package com.tungsten.fcl.ui.download;

public class MjyManifestInfo extends BaseManifest implements Comparable<MjyManifestInfo> {

    public String location;
    public int priority;

    @Override
    public int compareTo(MjyManifestInfo o) {
        if (priority > o.priority) {
            return -1;
        } else if (priority < o.priority) {
            return 1;
        } else {
            return 0;
        }
    }

}
