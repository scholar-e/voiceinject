package com.voiceinject;

import java.util.List;

/** Validation and expansion shared by settings and the send path. */
final class HotCommands {
    static final int LIMIT = 5;
    static boolean valid(String value) {
        return value.isEmpty() || (value.startsWith("/") && value.length() > 1
                && value.length() <= 256 && value.chars().noneMatch(c -> Character.isISOControl(c) || c == 0xA7));
    }
    static String forSpeech(String command, String speech) {
        if (command.isEmpty()) return speech;
        return command.contains("{text}") ? command.replace("{text}", speech) : command + " " + speech;
    }
    static int next(int selected, List<String> commands) {
        return selected + 1 >= commands.size() ? -1 : selected + 1;
    }
}
