package com.nullfuscator.obf;

import com.nullfuscator.obf.core.*;
import com.nullfuscator.obf.transform.AntiDecompilerTransformer;
import com.nullfuscator.obf.util.NameGenerator;
import com.nullfuscator.obf.util.ObfLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

public final class AntiDecompilerRegression implements Opcodes {

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        testAntiDecompilerExecution();
        System.out.println("PASS anti-decompiler cyclic signatures, traps, troll traces, and structural hardening");
    }

    private static void testAntiDecompilerExecution() throws Exception {
        ClassNode cn = new ClassNode();
        cn.version = V17;
        cn.access = ACC_PUBLIC;
        cn.name = "sample/CrasherApp";
        cn.superName = "java/lang/Object";

        MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(RETURN));
        cn.methods.add(init);

        MethodNode work = new MethodNode(ACC_PUBLIC | ACC_STATIC, "work", "(II)I", null, null);
        work.instructions.add(new VarInsnNode(ILOAD, 0));
        work.instructions.add(new VarInsnNode(ILOAD, 1));
        work.instructions.add(new InsnNode(IMUL));
        work.instructions.add(new InsnNode(IRETURN));
        cn.methods.add(work);

        MethodNode errorMethod = new MethodNode(ACC_PUBLIC | ACC_STATIC, "triggerError", "()V", null, null);
        errorMethod.instructions.add(new TypeInsnNode(NEW, "java/lang/RuntimeException"));
        errorMethod.instructions.add(new InsnNode(DUP));
        errorMethod.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false));
        errorMethod.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false));
        errorMethod.instructions.add(new InsnNode(RETURN));
        cn.methods.add(errorMethod);

        ObfConfig config = ObfConfig.parse("antiDecompiler { enabled: true, level: 3, cyclicSignatures: true, tryCatchTraps: true, cfrSwitchTraps: true, trapPercent: 100, trollStackTraces: true, structuralHardening: true }");
        ObfContext ctx = new ObfContext(config, 42, new ObfLog(false), new NameGenerator(new String[] { "I", "l" }));
        ctx.putClass(cn);
        ctx.initializePolicies();

        AntiDecompilerTransformer transformer = new AntiDecompilerTransformer();
        transformer.transform(ctx);

        check(cn.signature != null && cn.signature.contains("__F"), "Cyclic generic signature must be injected");
        check(work.tryCatchBlocks != null && work.tryCatchBlocks.size() >= 2, "Overlapping try-finally traps must be injected");
        check(cn.attrs != null && !cn.attrs.isEmpty(), "Structural hardening attributes must be present on class");

        boolean hasTableSwitch = false;
        boolean hasLdcHandle = false;
        boolean hasClashArray = false;
        for (AbstractInsnNode insn : work.instructions.toArray()) {
            if (insn instanceof TableSwitchInsnNode) {
                hasTableSwitch = true;
            }
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Handle) {
                hasLdcHandle = true;
            }
            if (insn instanceof IntInsnNode intInsn && intInsn.getOpcode() == NEWARRAY) {
                hasClashArray = true;
            }
        }
        check(hasTableSwitch, "CFR switch contiguity trap must be injected");
        check(hasLdcHandle, "LDC MethodHandle trap must be injected");
        check(hasClashArray, "Type clash trap must be injected");
        check(cn.methods.size() > 3, "Overloaded return type methods must be injected");

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        byte[] bytes = cw.toByteArray();

        ClassLoader cl = new ClassLoader(AntiDecompilerRegression.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("sample.CrasherApp")) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> clazz = cl.loadClass("sample.CrasherApp");
        Method m = clazz.getMethod("work", int.class, int.class);
        Object res = m.invoke(null, 6, 7);
        check(Integer.valueOf(42).equals(res), "Method execution must succeed and return 42");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(capturedErr));
            Method errMtd = clazz.getMethod("triggerError");
            errMtd.invoke(null);
        } finally {
            System.setErr(originalErr);
        }
        check(capturedErr.toString().contains("Stack trace unavailable"), "Troll stack trace banner must be printed");
    }
}
