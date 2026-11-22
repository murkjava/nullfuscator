package com.nullfuscator.obf.runtime;

import java.lang.management.ManagementFactory;
import java.util.Locale;

public final class DebugCheck {

    private DebugCheck() {
    }

    private static volatile int cached = 0;
    private static volatile long deadline;

    static {
        try {
            System.setProperty("jdk.attach.allowAttachSelf", "false");
        } catch (Throwable ignored) {
        }
    }

    private static boolean rejectAgents() {
        return false;
    }

    private static boolean rejectAttach() {
        return false;
    }

    public static boolean detected() {
        int c = cached;
        if (c == 0 || (c != 1 && System.nanoTime() - deadline >= 0)) {
            c = compute() ? 1 : 2;
            deadline = System.nanoTime() + 5_000_000_000L;
            cached = c;
        }
        return c == 1;
    }

    private static boolean compute() {
        try {
            for (String s : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String lower = s.toLowerCase(Locale.ROOT);
                if (lower.contains("jdwp") || lower.contains("-xdebug") || lower.contains("-xrunjdwp")) {
                    return true;
                }
                if (rejectAgents() && (lower.startsWith("-javaagent:")
                        || lower.startsWith("-agentpath:")
                        || lower.startsWith("-agentlib:")
                        || lower.contains("bytebuddy")
                        || lower.contains("aspectj"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (rejectAttach() && hasAttachedAgent()) {
            return true;
        }
        return false;
    }

    private static boolean hasAttachedAgent() {
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String name = t.getName();
                if (name != null) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.contains("jdwp") || lower.contains("debugger") || lower.contains("jdb")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String[] agentClasses = {
            "net.bytebuddy.agent.Installer",
            "software.coley.recaf.agent.Agent",
            "me.coley.recaf.agent.Agent",
            "com.intellij.rt.debugger.agent.CaptureAgent",
            "com.intellij.rt.debugger.agent.DebuggerAgent"
        };
        for (String cls : agentClasses) {
            try {
                Class.forName(cls);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
