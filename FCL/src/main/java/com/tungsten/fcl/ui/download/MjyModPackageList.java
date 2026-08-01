package com.tungsten.fcl.ui.download;

import java.util.List;

public class MjyModPackageList {

    public String title;
    public String name;
    public String version;
    public String minimumVersion;
    public String librariesLocation;
    public String objectsLocation;
    public String gameVersion;

    // Server address (host:port) from the modpack manifest, exposed to the game via the
    // -DtargetServer system property so custom mods can read and display it.
    public String server;

    public List<MjyModFeature> features;
    public List<MjyModTask> tasks;

}
