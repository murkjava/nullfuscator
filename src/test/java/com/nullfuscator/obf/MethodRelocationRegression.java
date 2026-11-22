package com.nullfuscator.obf;

import com.nullfuscator.obf.core.ObfConfig;
import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.transform.FieldPackingTransformer;
import com.nullfuscator.obf.transform.MethodRelocationTransformer;
import com.nullfuscator.obf.util.NameGenerator;
import com.nullfuscator.obf.util.ObfLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class MethodRelocationRegression implements Opcodes {

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        testMethodRelocationWithPrivateMembers();
        testFieldPackingAndRelocation();
        System.out.println("PASS method relocation across opaque carriers and field packing synergy");
    }

    private static void testMethodRelocationWithPrivateMembers() throws Exception {
        ObfConfig config = ObfConfig.parse("""
                methodRelocation { enabled: true, percent: 100, maxMethods: 16, carriers: 2, minInstructions: 4 }
                """);
        ObfContext ctx = new ObfContext(config, 42L, new ObfLog(false), new NameGenerator(new String[] { "I", "l" }));

        ClassNode worker = new ClassNode();
        worker.version = V17;
        worker.access = ACC_PUBLIC;
        worker.name = "sample/Worker";
        worker.superName = "java/lang/Object";

        worker.fields.add(new FieldNode(ACC_PRIVATE, "secret", "I", null, null));

        MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new InsnNode(ICONST_5));
        init.instructions.add(new FieldInsnNode(PUTFIELD, "sample/Worker", "secret", "I"));
        init.instructions.add(new InsnNode(RETURN));
        init.maxStack = 2;
        init.maxLocals = 1;
        worker.methods.add(init);

        MethodNode privHelper = new MethodNode(ACC_PRIVATE, "helper", "()I", null, null);
        privHelper.instructions.add(new VarInsnNode(ALOAD, 0));
        privHelper.instructions.add(new FieldInsnNode(GETFIELD, "sample/Worker", "secret", "I"));
        privHelper.instructions.add(new InsnNode(IRETURN));
        privHelper.maxStack = 1;
        privHelper.maxLocals = 1;
        worker.methods.add(privHelper);

        MethodNode calc = new MethodNode(ACC_PUBLIC, "calculate", "(II)I", null, null);
        calc.instructions.add(new VarInsnNode(ILOAD, 1));
        calc.instructions.add(new VarInsnNode(ILOAD, 2));
        calc.instructions.add(new InsnNode(IADD));
        calc.instructions.add(new IntInsnNode(BIPUSH, 3));
        calc.instructions.add(new InsnNode(IMUL));
        calc.instructions.add(new IntInsnNode(BIPUSH, 90));
        calc.instructions.add(new InsnNode(IXOR));
        calc.instructions.add(new IntInsnNode(BIPUSH, 7));
        calc.instructions.add(new InsnNode(IADD));
        calc.instructions.add(new InsnNode(IRETURN));
        calc.maxStack = 3;
        calc.maxLocals = 3;
        worker.methods.add(calc);

        ctx.putClass(worker);

        MethodRelocationTransformer transformer = new MethodRelocationTransformer();
        transformer.transform(ctx);

        boolean bridgeFound = false;
        String carrierName = null;
        for (AbstractInsnNode in : calc.instructions) {
            if (in instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC) {
                bridgeFound = true;
                carrierName = call.owner;
                break;
            }
        }
        check(bridgeFound, "calculate() must be replaced with bridge call to carrier");
        check(carrierName != null && !carrierName.equals(worker.name), "Carrier class must be distinct");

        ClassNode carrier = ctx.getClass(carrierName);
        check(carrier != null, "Carrier class must be registered in context");
        check(!carrier.methods.isEmpty(), "Carrier class must contain methods");

        Map<String, byte[]> bytecodeMap = new HashMap<>();
        for (ClassNode cn : ctx.classes()) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            bytecodeMap.put(cn.name.replace('/', '.'), cw.toByteArray());
        }

        ClassLoader cl = new ClassLoader(MethodRelocationRegression.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = bytecodeMap.get(name);
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> workerClass = cl.loadClass("sample.Worker");
        Object instance = workerClass.getConstructor().newInstance();
        Method calcMethod = workerClass.getMethod("calculate", int.class, int.class);
        int result = (int) calcMethod.invoke(instance, 10, 20);
        check(result == 7, "Relocated calculate() execution must produce correct result 7, got: " + result);
    }

    private static void testFieldPackingAndRelocation() throws Exception {
        ObfConfig config = ObfConfig.parse("""
                fieldPacking { enabled: true, minFields: 1 }
                methodRelocation { enabled: true, percent: 100, maxMethods: 16, carriers: 2, minInstructions: 4 }
                """);
        ObfContext ctx = new ObfContext(config, 99L, new ObfLog(false), new NameGenerator(new String[] { "I", "l" }));

        ClassNode target = new ClassNode();
        target.version = V17;
        target.access = ACC_PUBLIC;
        target.name = "sample/StatefulService";
        target.superName = "java/lang/Object";

        target.fields.add(new FieldNode(ACC_PRIVATE, "factor", "I", null, null));

        MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new IntInsnNode(BIPUSH, 4));
        init.instructions.add(new FieldInsnNode(PUTFIELD, "sample/StatefulService", "factor", "I"));
        init.instructions.add(new InsnNode(RETURN));
        init.maxStack = 2;
        init.maxLocals = 1;
        target.methods.add(init);

        MethodNode compute = new MethodNode(ACC_PUBLIC, "compute", "(I)I", null, null);
        compute.instructions.add(new VarInsnNode(ILOAD, 1));
        compute.instructions.add(new VarInsnNode(ALOAD, 0));
        compute.instructions.add(new FieldInsnNode(GETFIELD, "sample/StatefulService", "factor", "I"));
        compute.instructions.add(new InsnNode(IMUL));
        compute.instructions.add(new IntInsnNode(BIPUSH, 10));
        compute.instructions.add(new InsnNode(IADD));
        compute.instructions.add(new InsnNode(IRETURN));
        compute.maxStack = 3;
        compute.maxLocals = 2;
        target.methods.add(compute);

        ctx.putClass(target);

        new FieldPackingTransformer().transform(ctx);
        check(target.fields.stream().anyMatch(f -> "[Ljava/lang/Object;".equals(f.desc)),
                "FieldPacking must introduce Object[] state vector");

        new MethodRelocationTransformer().transform(ctx);

        Map<String, byte[]> bytecodeMap = new HashMap<>();
        for (ClassNode cn : ctx.classes()) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            bytecodeMap.put(cn.name.replace('/', '.'), cw.toByteArray());
        }

        ClassLoader cl = new ClassLoader(MethodRelocationRegression.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = bytecodeMap.get(name);
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> serviceClass = cl.loadClass("sample.StatefulService");
        Object inst = serviceClass.getConstructor().newInstance();
        Method m = serviceClass.getMethod("compute", int.class);
        int val = (int) m.invoke(inst, 5);
        check(val == 30, "Packed and relocated compute() must yield 5 * 4 + 10 = 30, got: " + val);
    }
}
