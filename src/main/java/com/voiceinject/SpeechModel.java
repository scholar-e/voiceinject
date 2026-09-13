package com.voiceinject;

import java.net.URI;

public enum SpeechModel {
    SMALL("vosk-model-small-en-us-0.15", "40 MB"),
    LGRAPH("vosk-model-en-us-0.22-lgraph", "128 MB"),
    FULL("vosk-model-en-us-0.22", "1.8 GB");

    private static final String BASE_URL = "https://alphacephei.com/vosk/models/";

    private final String directory;
    private final String downloadSize;

    SpeechModel(String directory, String downloadSize) {
        this.directory = directory;
        this.downloadSize = downloadSize;
    }

    String directory() {
        return directory;
    }

    String downloadSize() {
        return downloadSize;
    }

    URI downloadUri() {
        return URI.create(BASE_URL + directory + ".zip");
    }

    SpeechModel next() {
        SpeechModel[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
