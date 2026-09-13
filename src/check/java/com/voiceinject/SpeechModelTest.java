package com.voiceinject;

public final class SpeechModelTest {
    public static void main(String[] args) {
        if (SpeechModel.SMALL.next() != SpeechModel.LGRAPH
                || SpeechModel.LGRAPH.next() != SpeechModel.FULL
                || SpeechModel.FULL.next() != SpeechModel.SMALL) {
            throw new AssertionError("model selector must cycle through every model");
        }
        for (SpeechModel model : SpeechModel.values()) {
            String expectedFile = model.directory() + ".zip";
            if (!model.downloadUri().getPath().endsWith(expectedFile)) {
                throw new AssertionError("wrong download for " + model);
            }
        }
        System.out.println("Speech model tests passed");
    }
}
