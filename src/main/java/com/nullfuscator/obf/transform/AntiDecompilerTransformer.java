package com.nullfuscator.obf.transform;

import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.core.Transformer;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class AntiDecompilerTransformer implements Transformer {

    private static final String[] FIELD_DESCS = {
            "I", "J", "Z", "F", "D", "B", "C", "S",
            "Ljava/lang/String;", "Ljava/lang/Object;", "[I", "[Ljava/lang/Object;"
    };

    private static final String[] RETURN_DESCS = {
            "Ljava/lang/Object;", "Ljava/lang/String;", "I", "Z", "J", "D", "F", "[I", "V"
    };

    private static final String[] BAD_NAMES = {
            "do", "goto", "true", "false", "null", "for", "if", "new",
            "class", "int", "void", "return", "const", "1", "2do", "0x0"
    };

    private static final String[] CFR_TRAP_EXCEPTIONS = {
            "java/lang/RuntimeException",
            "java/lang/IllegalStateException",
            "java/lang/IllegalArgumentException",
            "java/lang/ArithmeticException",
            "java/lang/IndexOutOfBoundsException",
            "java/lang/UnsupportedOperationException"
    };

    @Override
    public String id() {
        return "antiDecompiler";
    }

    @Override
    public String description() {
        return "class-file-legal, source-illegal member poison and AST crasher traps";
    }

    @Override
    public void transform(ObfContext ctx) {
        var section = ctx.config().section(id());
        int level = clamp(section.getInt("level", 1), 1, 3);
        boolean cyclicSignatures = section.getBoolean("cyclicSignatures", level >= 2);
        boolean tryCatchTraps = section.getBoolean("tryCatchTraps", level >= 2);
        boolean trollStackTraces = section.getBoolean("trollStackTraces", level >= 2);
        boolean structuralHardening = section.getBoolean("structuralHardening", level >= 2);
        boolean cfrSwitchTraps = section.getBoolean("cfrSwitchTraps", level >= 2);
        String trollMessage = section.getString("trollMessage", "  /\\_/\\\n ( o.o )  Stack trace unavailable\n  > ^ <");
        int trapPercent = clamp(section.getInt("trapPercent", 80), 0, 100);
        int cyclicDepth = clamp(section.getInt("cyclicDepth", 48), 8, 128);
        boolean skipAnnotatedClasses = section.getBoolean("skipAnnotatedClasses", false);

        Random rnd = ctx.random();
        int classes = 0;
        int fieldPoison = 0;
        int methodPoison = 0;
        int illegal = 0;
        int cyclicCount = 0;
        int trapCount = 0;
        int cfrSwitchTrapCount = 0;
        int trollCount = 0;
        int structuralCount = 0;

        for (ClassNode cn : ctx.targets(id())) {
            if (ctx.isHotClass(cn) || ObfContext.isMixin(cn) || ctx.isMixinPackage(cn.name)) {
                continue;
            }

            if ((cn.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) {
                continue;
            }

            if (cyclicSignatures && cn.signature == null) {
                cn.signature = cyclicTypeVariableSignature(cn.superName, cn.interfaces, cyclicDepth);
                if (level >= 3 && cn.fields != null) {
                    String fname = ctx.names().next();
                    cn.fields.add(new FieldNode(
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            fname,
                            "Ljava/util/List;",
                            nestedListSignature(cn.name, Math.min(cyclicDepth, 64)),
                            null));
                }
                cyclicCount++;
            }

            if (tryCatchTraps) {
                for (MethodNode mn : cn.methods) {
                    if (mn.name.startsWith("<")
                            || (mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                            || mn.instructions == null
                            || mn.instructions.size() < 4
                            || ctx.isHotPath(cn, mn)) {
                        continue;
                    }
                    if (rnd.nextInt(100) < trapPercent) {
                        injectDecompilerTrap(mn);
                        trapCount++;
                    }
                }
            }

            if (cfrSwitchTraps) {
                for (MethodNode mn : cn.methods) {
                    if (mn.name.startsWith("<")
                            || (mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                            || mn.instructions == null
                            || mn.instructions.size() < 4
                            || ctx.isHotPath(cn, mn)) {
                        continue;
                    }
                    if (rnd.nextInt(100) < trapPercent) {
                        injectCfrSwitchTrap(mn, rnd);
                        cfrSwitchTrapCount++;
                    }
                }
            }

            if (trollStackTraces) {
                trollCount += applyTrollStackTraces(cn, trollMessage, ctx);
            }

            if (structuralHardening) {
                applyStructuralHardening(cn, rnd);
                structuralCount++;
            }

            if (skipAnnotatedClasses && reflectionSensitive(cn)) {
                continue;
            }

            fieldPoison += addFieldCollisions(cn, level, rnd, ctx);
            if (level >= 2) {
                methodPoison += addReturnTypeCollisions(cn, level, rnd);
            }
            if (level >= 3) {
                illegal += addIllegalNamedMembers(cn, rnd);
            }
            classes++;
        }

        ctx.log().debug("antiDecompiler: classes=" + classes
                + " fieldPoison=" + fieldPoison
                + " methodPoison=" + methodPoison
                + " illegal=" + illegal
                + " cyclicSigs=" + cyclicCount
                + " traps=" + trapCount
                + " cfrSwitchTraps=" + cfrSwitchTrapCount
                + " trollTraces=" + trollCount
                + " structuralHardening=" + structuralCount
                + " (level=" + level + ")");
    }

    private static String cyclicTypeVariableSignature(String superName, List<String> interfaces, int depth) {
        int variables = Math.max(2, Math.min(64, depth / 4));
        StringBuilder sb = new StringBuilder(variables * 40 + 64);
        sb.append('<');
        for (int i = 0; i < variables; i++) {
            sb.append("__F").append(i).append(":Ljava/util/List<T__F")
                    .append((i + 1) % variables)
                    .append(";>;");
        }
        String sName = (superName == null || superName.isEmpty()) ? "java/lang/Object" : superName;
        sb.append(">L").append(sName).append(';');
        if (interfaces != null) {
            for (String iface : interfaces) {
                sb.append('L').append(iface).append(';');
            }
        }
        return sb.toString();
    }

    private static String nestedListSignature(String owner, int depth) {
        StringBuilder sb = new StringBuilder(depth * 18 + owner.length() + 4);
        for (int i = 0; i < depth; i++) {
            sb.append("Ljava/util/List<");
        }
        sb.append('L').append(owner).append(';');
        for (int i = 0; i < depth; i++) {
            sb.append(">;");
        }
        return sb.toString();
    }

    private static void injectDecompilerTrap(MethodNode method) {
        LabelNode handler = new LabelNode();
        LabelNode outerStart = new LabelNode();
        LabelNode innerStart = new LabelNode();
        LabelNode innerEnd = new LabelNode();
        LabelNode outerEnd = new LabelNode();
        LabelNode resume = new LabelNode();

        if (method.tryCatchBlocks == null) {
            method.tryCatchBlocks = new ArrayList<>();
        }
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                outerStart, outerEnd, handler, "java/lang/Throwable"));
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                innerStart, innerEnd, handler, null));

        InsnList trap = new InsnList();
        trap.add(new InsnNode(Opcodes.ICONST_0));
        trap.add(new JumpInsnNode(Opcodes.IFNE, outerStart));
        trap.add(new JumpInsnNode(Opcodes.GOTO, resume));
        trap.add(outerStart);
        trap.add(innerStart);
        trap.add(new InsnNode(Opcodes.ACONST_NULL));
        trap.add(new InsnNode(Opcodes.ATHROW));
        trap.add(innerEnd);
        trap.add(outerEnd);
        trap.add(handler);
        trap.add(new InsnNode(Opcodes.POP));
        trap.add(new JumpInsnNode(Opcodes.GOTO, resume));
        trap.add(resume);

        method.instructions.insert(trap);
        if (method.maxStack < 1) {
            method.maxStack = 1;
        }
    }

    private static void injectCfrSwitchTrap(MethodNode method, Random rnd) {
        LabelNode lReal = new LabelNode();
        LabelNode lTrap = new LabelNode();
        LabelNode lSw1 = new LabelNode();
        LabelNode lSw2 = new LabelNode();
        LabelNode lCase1_0 = new LabelNode();
        LabelNode lCase1_1 = new LabelNode();
        LabelNode lCase2_0 = new LabelNode();
        LabelNode lCase2_1 = new LabelNode();

        InsnList trap = new InsnList();

        trap.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false));
        trap.add(new InsnNode(Opcodes.LCONST_0));
        trap.add(new InsnNode(Opcodes.LCMP));
        trap.add(new JumpInsnNode(Opcodes.IFGE, lReal));

        trap.add(lTrap);

        trap.add(lSw1);
        trap.add(new InsnNode(Opcodes.ICONST_0));
        trap.add(new TableSwitchInsnNode(0, 1, lCase1_0, lSw2, lCase1_1));

        addThrowBlock(trap, lCase1_0, pickCfrException(rnd));

        trap.add(lSw2);
        trap.add(new InsnNode(Opcodes.ICONST_0));
        trap.add(new TableSwitchInsnNode(0, 1, lCase2_0, lCase1_1, lCase2_1));

        addThrowBlock(trap, lCase2_0, pickCfrException(rnd));
        addThrowBlock(trap, lCase1_1, pickCfrException(rnd));
        addThrowBlock(trap, lCase2_1, pickCfrException(rnd));

        trap.add(lReal);

        method.instructions.insert(trap);
        if (method.maxStack < 4) {
            method.maxStack = 4;
        }
    }

    private static void addThrowBlock(InsnList il, LabelNode lbl, String exType) {
        il.add(lbl);
        il.add(new TypeInsnNode(Opcodes.NEW, exType));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, exType, "<init>", "()V", false));
        il.add(new InsnNode(Opcodes.ATHROW));
    }

    private static String pickCfrException(Random rnd) {
        return CFR_TRAP_EXCEPTIONS[rnd.nextInt(CFR_TRAP_EXCEPTIONS.length)];
    }

    private int addFieldCollisions(ClassNode cn, int level, Random rnd, ObfContext ctx) {
        if (cn.fields == null) {
            cn.fields = new ArrayList<>();
        }

        Set<String> present = new HashSet<>();
        List<String> baseNames = new ArrayList<>();
        for (FieldNode f : cn.fields) {
            present.add(f.name + " " + f.desc);
            if (!f.name.equals("serialVersionUID") && !f.name.equals("serialPersistentFields")) {
                baseNames.add(f.name);
            }
        }

        int want = level;
        int added = 0;
        for (int i = 0; i < want; i++) {
            String base = baseNames.isEmpty()
                    ? ctx.names().next()
                    : baseNames.get(rnd.nextInt(baseNames.size()));
            String desc = pickUnusedFieldDesc(base, present, rnd);
            if (desc == null) {
                continue;
            }

            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    base, desc, null, null));
            present.add(base + " " + desc);
            if (!baseNames.contains(base)) {
                baseNames.add(base);
            }
            added++;
        }
        return added;
    }

    private String pickUnusedFieldDesc(String name, Set<String> present, Random rnd) {
        int start = rnd.nextInt(FIELD_DESCS.length);
        for (int k = 0; k < FIELD_DESCS.length; k++) {
            String d = FIELD_DESCS[(start + k) % FIELD_DESCS.length];
            if (!present.contains(name + " " + d)) {
                return d;
            }
        }
        return null;
    }

    private int addReturnTypeCollisions(ClassNode cn, int level, Random rnd) {
        Set<String> present = new HashSet<>();
        List<MethodNode> candidates = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            present.add(m.name + m.desc);
            if (m.name.equals("<init>") || m.name.equals("<clinit>")) {
                continue;
            }
            if (MethodRenamer.isSerializationHook(m)) {
                continue;
            }
            candidates.add(m);
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        int want = level - 1;
        int added = 0;
        for (int i = 0; i < want; i++) {
            MethodNode src = candidates.get(rnd.nextInt(candidates.size()));
            String params = src.desc.substring(0, src.desc.indexOf(')') + 1);
            String ret = pickUnusedReturn(src.name, params, present, rnd);
            if (ret == null) {
                continue;
            }

            String desc = params + ret;
            MethodNode mn = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    src.name, desc, null, null);
            emitReturnStub(mn.instructions, ret);
            cn.methods.add(mn);
            present.add(src.name + desc);
            added++;
        }
        return added;
    }

    private String pickUnusedReturn(String name, String params, Set<String> present, Random rnd) {
        int start = rnd.nextInt(RETURN_DESCS.length);
        for (int k = 0; k < RETURN_DESCS.length; k++) {
            String ret = RETURN_DESCS[(start + k) % RETURN_DESCS.length];
            if (!present.contains(name + params + ret)) {
                return ret;
            }
        }
        return null;
    }

    private int addIllegalNamedMembers(ClassNode cn, Random rnd) {
        if (cn.fields == null) {
            cn.fields = new ArrayList<>();
        }

        Set<String> fieldKeys = new HashSet<>();
        for (FieldNode f : cn.fields) {
            fieldKeys.add(f.name + " " + f.desc);
        }
        Set<String> methodKeys = new HashSet<>();
        for (MethodNode m : cn.methods) {
            methodKeys.add(m.name + m.desc);
        }

        int added = 0;
        for (int i = 0; i < 2; i++) {
            String nm = BAD_NAMES[rnd.nextInt(BAD_NAMES.length)];
            String desc = FIELD_DESCS[rnd.nextInt(FIELD_DESCS.length)];
            if (fieldKeys.add(nm + " " + desc)) {
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        nm, desc, null, null));
                added++;
            }
        }

        String mnm = BAD_NAMES[rnd.nextInt(BAD_NAMES.length)];
        if (methodKeys.add(mnm + "()V")) {
            MethodNode mn = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    mnm, "()V", null, null);
            mn.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(mn);
            added++;
        }
        return added;
    }

    private static void emitReturnStub(InsnList il, String ret) {
        char c = ret.charAt(0);
        switch (c) {
            case 'V':
                il.add(new InsnNode(Opcodes.RETURN));
                break;
            case 'J':
                il.add(new InsnNode(Opcodes.LCONST_0));
                il.add(new InsnNode(Opcodes.LRETURN));
                break;
            case 'F':
                il.add(new InsnNode(Opcodes.FCONST_0));
                il.add(new InsnNode(Opcodes.FRETURN));
                break;
            case 'D':
                il.add(new InsnNode(Opcodes.DCONST_0));
                il.add(new InsnNode(Opcodes.DRETURN));
                break;
            case 'L':
            case '[':
                il.add(new InsnNode(Opcodes.ACONST_NULL));
                il.add(new InsnNode(Opcodes.ARETURN));
                break;
            default:
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(Opcodes.IRETURN));
                break;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static boolean reflectionSensitive(ClassNode cn) {
        if (cn.visibleAnnotations != null && !cn.visibleAnnotations.isEmpty()) {
            return true;
        }
        for (FieldNode field : cn.fields) {
            if (field.visibleAnnotations != null && !field.visibleAnnotations.isEmpty()) {
                return true;
            }
        }
        for (MethodNode method : cn.methods) {
            if (method.visibleAnnotations != null && !method.visibleAnnotations.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int applyTrollStackTraces(ClassNode cn, String message, ObfContext ctx) {
        String helperName = "__nf$troll";
        int suffix = 0;
        while (hasMethodNamed(cn, helperName)) {
            helperName = "__nf$troll$" + (++suffix);
        }
        Set<String> neededHelpers = new HashSet<>();
        int rewritten = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.name.equals(helperName)) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.name.equals("printStackTrace")
                        || !isThrowableOwner(ctx, call.owner)
                        || !isSupportedPrintStackTrace(call.desc)) {
                    continue;
                }
                String helperDesc = helperDescriptor(call.desc);
                mn.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, helperName, helperDesc, false));
                neededHelpers.add(helperDesc);
                rewritten++;
            }
        }
        for (String desc : neededHelpers) {
            cn.methods.add(buildTrollHelper(helperName, desc, message));
        }
        return rewritten;
    }

    private static MethodNode buildTrollHelper(String name, String desc, String message) {
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        if (desc.equals("(Ljava/lang/Throwable;)V")) {
            helper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
            helper.instructions.add(new LdcInsnNode(message));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
        } else {
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            helper.instructions.add(new LdcInsnNode(message));
            String owner = desc.contains("PrintWriter") ? "java/io/PrintWriter" : "java/io/PrintStream";
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "println", "(Ljava/lang/String;)V", false));
        }
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.maxStack = 2;
        helper.maxLocals = desc.equals("(Ljava/lang/Throwable;)V") ? 1 : 2;
        return helper;
    }

    private static boolean hasMethodNamed(ClassNode cn, String name) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupportedPrintStackTrace(String desc) {
        return desc.equals("()V")
                || desc.equals("(Ljava/io/PrintStream;)V")
                || desc.equals("(Ljava/io/PrintWriter;)V");
    }

    private static String helperDescriptor(String original) {
        return "(Ljava/lang/Throwable;" + original.substring(1);
    }

    private static boolean isThrowableOwner(ObfContext ctx, String owner) {
        if (owner == null) {
            return false;
        }
        if (owner.equals("java/lang/Throwable")
                || owner.equals("java/lang/Exception")
                || owner.equals("java/lang/Error")
                || owner.equals("java/lang/RuntimeException")) {
            return true;
        }
        ClassNode cn = ctx.getClass(owner);
        if (cn != null && cn.superName != null) {
            return isThrowableOwner(ctx, cn.superName);
        }
        return false;
    }

    private static void applyStructuralHardening(ClassNode cn, Random rnd) {
        if (cn.attrs == null) {
            cn.attrs = new ArrayList<>();
        }
        for (int i = 0; i < 2; i++) {
            byte[] payload = new byte[48 + rnd.nextInt(32)];
            rnd.nextBytes(payload);
            cn.attrs.add(new OpaqueAttribute("NullfAttr_" + Integer.toHexString(rnd.nextInt(0xFFFFFF)), payload));
        }
        for (MethodNode mn : cn.methods) {
            if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                if (mn.attrs == null) {
                    mn.attrs = new ArrayList<>();
                }
                byte[] payload = new byte[24 + rnd.nextInt(24)];
                rnd.nextBytes(payload);
                mn.attrs.add(new OpaqueAttribute("NullfMethodAttr_" + Integer.toHexString(rnd.nextInt(0xFFFFFF)), payload));
            }
        }
        for (FieldNode fn : cn.fields) {
            if (fn.attrs == null) {
                fn.attrs = new ArrayList<>();
            }
            byte[] payload = new byte[16 + rnd.nextInt(16)];
            rnd.nextBytes(payload);
            fn.attrs.add(new OpaqueAttribute("NullfFieldAttr_" + Integer.toHexString(rnd.nextInt(0xFFFFFF)), payload));
        }
    }

    public static final class OpaqueAttribute extends Attribute {
        private final byte[] data;

        public OpaqueAttribute(String type, byte[] data) {
            super(type);
            this.data = data;
        }

        @Override
        protected ByteVector write(ClassWriter cw, byte[] code, int len, int maxStack, int maxLocals) {
            return new ByteVector(data.length).putByteArray(data, 0, data.length);
        }
    }
}
