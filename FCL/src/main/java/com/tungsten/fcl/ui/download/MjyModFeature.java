package com.tungsten.fcl.ui.download;

public class MjyModFeature {

    public enum Recommendation {
        STARRED,
        AVOID;
    }

    public String name;
    public String description;
    public Recommendation recommendation;
    public boolean selected;
}
