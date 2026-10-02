package com.ebs.biocrop.service;

public enum ImageResolution {
    ORIGINAL("original"),
    MID("mid"),
    LOW("low");

    private final String folder;

    ImageResolution(String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}