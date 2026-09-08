package com.voiceinject;

import java.util.List;

public final class HotCommandsTest {
    public static void main(String[] args) {
        equal(HotCommands.forSpeech("/msg Alex {text}", "hello there"), "/msg Alex hello there");
        equal(HotCommands.forSpeech("/msg Alex", "hello"), "/msg Alex hello");
        equal(HotCommands.forSpeech("", "hello"), "hello");
        equal(HotCommands.forSpeech("/msg Alex {text}", "$5 \\ home"), "/msg Alex $5 \\ home");
        List<String> commands = List.of("/home", "/msg Alex {text}");
        if (HotCommands.next(-1, commands) != 0 || HotCommands.next(0, commands) != 1
                || HotCommands.next(1, commands) != -1 || HotCommands.next(-1, List.of()) != -1)
            throw new AssertionError("cycling must include normal chat and wrap");
        for (String valid : List.of("", "/home", "/msg Alex {text}"))
            if (!HotCommands.valid(valid)) throw new AssertionError(valid);
        for (String invalid : List.of("home", "/", "/home\n/op Alex", "/" + "a".repeat(256)))
            if (HotCommands.valid(invalid)) throw new AssertionError("accepted invalid command");
        System.out.println("Hot command tests passed");
    }
    private static void equal(String actual, String expected) {
        if (!actual.equals(expected)) throw new AssertionError(actual);
    }
}
