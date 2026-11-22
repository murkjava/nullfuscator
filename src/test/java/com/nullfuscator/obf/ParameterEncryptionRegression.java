package com.nullfuscator.obf;

import com.nullfuscator.obf.core.ObfConfig;
import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.transform.ParameterEncryptionTransformer;
import com.nullfuscator.obf.util.NameGenerator;
import com.nullfuscator.obf.util.ObfLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;

public final class ParameterEncryptionRegression implements Opcodes {

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        testParameterEncryption();
        System.out.println("PASS parameter encryption XOR encoding and decoding");
    }

    private static void testParameterEncryption() throws Exception {
        ClassNode cn = new ClassNode();
        cn.version = V17;
        cn.access = ACC_PUBLIC;
        cn.name = "sample/ParamApp";
        cn.superName = "java/lang/Object";

        MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(RETURN));
        cn.methods.add(init);

        MethodNode target = new MethodNode(ACC_PRIVATE | ACC_STATIC, "secretCompute", "(ILjava/lang/String;J)J", null, null);
        target.instructions.add(new VarInsnNode(ILOAD, 0));
        target.instructions.add(new InsnNode(I2L));
        target.instructions.add(new VarInsnNode(LLOAD, 2));
        target.instructions.add(new InsnNode(LADD));
        target.instructions.add(new VarInsnNode(ALOAD, 1));
        target.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        target.instructions.add(new InsnNode(I2L));
        target.instructions.add(new InsnNode(LADD));
        target.instructions.add(new InsnNode(LRETURN));
        target.maxStack = 4;
        target.maxLocals = 4;
        cn.methods.add(target);

        MethodNode caller = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "(ILjava/lang/String;J)J", null, null);
        caller.instructions.add(new VarInsnNode(ILOAD, 0));
        caller.instructions.add(new VarInsnNode(ALOAD, 1));
        caller.instructions.add(new VarInsnNode(LLOAD, 2));
        caller.instructions.add(new MethodInsnNode(INVOKESTATIC, "sample/ParamApp", "secretCompute", "(ILjava/lang/String;J)J", false));
        caller.instructions.add(new InsnNode(LRETURN));
        caller.maxStack = 4;
        caller.maxLocals = 4;
        cn.methods.add(caller);

        ObfConfig config = ObfConfig.parse("parameterEncryption { enabled: true, probability: 100 }");
        ObfContext ctx = new ObfContext(config, 12345, new ObfLog(false), new NameGenerator(new String[] { "a", "b" }));
        ctx.putClass(cn);
        ctx.initializePolicies();

        ParameterEncryptionTransformer transformer = new ParameterEncryptionTransformer();
        transformer.transform(ctx);

        boolean foundXorInTarget = false;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == IXOR || insn.getOpcode() == LXOR) {
                foundXorInTarget = true;
                break;
            }
        }
        check(foundXorInTarget, "Target method must contain XOR decode instructions");

        boolean foundXorInCaller = false;
        for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == IXOR || insn.getOpcode() == LXOR) {
                foundXorInCaller = true;
                break;
            }
        }
        check(foundXorInCaller, "Caller method must contain XOR encode instructions");

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        byte[] bytes = cw.toByteArray();

        ClassLoader cl = new ClassLoader(ParameterEncryptionRegression.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("sample.ParamApp")) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> clazz = cl.loadClass("sample.ParamApp");
        Method runMtd = clazz.getMethod("run", int.class, String.class, long.class);
        Object result = runMtd.invoke(null, 10, "hello", 100L);
        check(Long.valueOf(115L).equals(result), "Decoded execution must yield 115");
    }
}
