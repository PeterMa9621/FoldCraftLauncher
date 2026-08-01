package com.tungsten.fcl.ui.download;

public class MjyManifestInfo extends BaseManifest implements Comparable<MjyManifestInfo> {

    public String location;
    public int priority;

    // Server address (host:port) carried from the modpack manifest, used to expose the
    // target server to the game via the -DtargetServer system property (see the desktop
    // launcher's ManifestInfoEnumerator / LauncherFrame for the original mechanism).
    public String server;

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
