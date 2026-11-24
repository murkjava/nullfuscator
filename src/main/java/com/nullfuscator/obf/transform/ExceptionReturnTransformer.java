package com.nullfuscator.obf.transform;

import com.nullfuscator.obf.core.Limits;
import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.core.Transformer;
import com.nullfuscator.obf.util.BytecodeNoise;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class ExceptionReturnTransformer implements Transformer {

    @Override
    public String id() {
        return "exceptionReturn";
    }

    @Override
    public String description() {
        return "route typed returns through a caught control exception";
    }

    @Override
    public void transform(ObfContext ctx) {
        ctx.initializePolicies();
        int percent = clamp(ctx.config().section(id()).getInt("percent", 100), 0, 100);
        if (percent == 0) {
            return;
        }

        List<ClassNode> targets = ctx.targets(id());
        int version = Opcodes.V1_7;
        for (ClassNode cn : targets) {
            version = Math.max(version, cn.version & 0xFFFF);
        }

        Random rnd = ctx.random();
        int tokenCount = Math.max(2, Math.min(5, 1 + targets.size() / 15));
        Map<String, String> tokenTakeMethods = new HashMap<>();
        List<String> tokenNames = new ArrayList<>();
        List<ClassNode> tokens = new ArrayList<>();
        for (int i = 0; i < tokenCount; i++) {
            String pkg = BytecodeNoise.randomPackage(ctx, "rt");
            String tName;
            do {
                tName = pkg + ctx.names().next();
            } while (ctx.getClass(tName) != null);
            ctx.names().reserve(tName);
            String fieldName = ctx.names().next();
            String takeName = ctx.names().next();
            tokenTakeMethods.put(tName, takeName);
            ClassNode tNode = buildToken(tName, version, rnd, fieldName, takeName);
            BytecodeNoise.decorateCarrier(tNode, ctx);
            tokenNames.add(tName);
            tokens.add(tNode);
        }

        int methods = 0;
        int sites = 0;
        Set<String> usedTokens = new HashSet<>();
        for (ClassNode cn : targets) {
            if ((cn.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) {
                continue;
            }
            for (MethodNode mn : cn.methods) {
                if (!ctx.isInputMethod(mn) || ctx.isHotPath(cn, mn)) {
                    continue;
                }
                int count = eligibleReturnCount(mn);
                if (count == 0 || rnd.nextInt(100) >= percent) {
                    continue;
                }
                String tokenName = tokenNames.get(rnd.nextInt(tokenNames.size()));
                transformMethod(mn, tokenName, tokenTakeMethods.get(tokenName));
                usedTokens.add(tokenName);
                methods++;
                sites += count;
            }
        }

        if (methods > 0) {
            for (ClassNode t : tokens) {
                if (usedTokens.contains(t.name)) {
                    ctx.putClass(t);
                }
            }
        }
        ctx.log().debug("exceptionReturn: routed " + sites + " sites across " + methods + " methods");
    }

    private static int eligibleReturnCount(MethodNode mn) {
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return 0;
        }
        if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
            return 0;
        }
        if (mn.instructions == null || mn.instructions.size() == 0) {
            return 0;
        }
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            return 0;
        }
        if (Limits.oversizeMethod(mn)) {
            return 0;
        }

        int expected = Type.getReturnType(mn.desc).getOpcode(Opcodes.IRETURN);
        int count = 0;
        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in.getOpcode() == expected) {
                count++;
            }
        }
        return count;
    }

    private static void transformMethod(MethodNode mn, String tokenName, String takeName) {
        Type ret = Type.getReturnType(mn.desc);
        int returnOpcode = ret.getOpcode(Opcodes.IRETURN);
        int valueLocal = mn.maxLocals;
        if (ret.getSort() != Type.VOID) {
            mn.maxLocals += ret.getSize();
        }

        LabelNode exit = new LabelNode();
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in.getOpcode() != returnOpcode) {
                continue;
            }
            InsnList replacement = new InsnList();
            if (ret.getSort() != Type.VOID) {
                replacement.add(new VarInsnNode(ret.getOpcode(Opcodes.ISTORE), valueLocal));
            }
            replacement.add(new JumpInsnNode(Opcodes.GOTO, exit));
            mn.instructions.insertBefore(in, replacement);
            mn.instructions.remove(in);
        }

        mn.instructions.add(exit);
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                tokenName, takeName, "()L" + tokenName + ";", false));
        mn.instructions.add(start);
        mn.instructions.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions.add(end);
        mn.instructions.add(handler);
        mn.instructions.add(new InsnNode(Opcodes.POP));
        if (ret.getSort() != Type.VOID) {
            mn.instructions.add(new VarInsnNode(ret.getOpcode(Opcodes.ILOAD), valueLocal));
        }
        mn.instructions.add(new InsnNode(returnOpcode));

        if (mn.tryCatchBlocks == null) {
            mn.tryCatchBlocks = new ArrayList<>();
        }
        mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, tokenName));
    }

    private static ClassNode buildToken(String name, int version, Random rnd, String fieldName, String takeName) {
        String[] bases = {
                "java/lang/RuntimeException",
                "java/lang/IllegalStateException",
                "java/lang/IllegalArgumentException",
                "java/lang/IndexOutOfBoundsException",
                "java/lang/UnsupportedOperationException"
        };
        String superName = bases[rnd.nextInt(bases.length)];

        ClassNode cn = new ClassNode();
        cn.version = version;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        cn.name = name;
        cn.superName = superName;
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                fieldName, "L" + name + ";", null, null));

        MethodNode init = new MethodNode(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(init);

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, name));
        clinit.instructions.add(new InsnNode(Opcodes.DUP));
        clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false));
        clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, name, fieldName, "L" + name + ";"));
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(clinit);

        MethodNode take = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                takeName, "()L" + name + ";", null, null);
        take.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, name, fieldName, "L" + name + ";"));
        take.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(take);

        return cn;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}