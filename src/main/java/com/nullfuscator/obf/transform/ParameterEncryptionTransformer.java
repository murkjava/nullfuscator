package com.nullfuscator.obf.transform;

import com.nullfuscator.obf.core.Limits;
import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.core.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class ParameterEncryptionTransformer implements Transformer {

    @Override
    public String id() {
        return "parameterEncryption";
    }

    @Override
    public String description() {
        return "encrypt method arguments and callsites with polymorphic XOR masks";
    }

    @Override
    public void transform(ObfContext ctx) {
        var section = ctx.config().section(id());
        int probability = Math.max(0, Math.min(100, section.getInt("probability", 40)));
        if (probability == 0) {
            return;
        }

        Random rnd = ctx.random();
        int totalMethods = 0;
        int totalCallsites = 0;

        for (ClassNode cn : ctx.targets(id())) {
            if (ObfContext.isMixin(cn) || ctx.isMixinPackage(cn.name)) {
                continue;
            }
            if ((cn.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) {
                continue;
            }

            Map<String, Map<Integer, Long>> targetMethods = new HashMap<>();
            for (MethodNode mn : cn.methods) {
                if (!canEncrypt(cn, mn, ctx) || rnd.nextInt(100) >= probability) {
                    continue;
                }
                Type[] args = Type.getArgumentTypes(mn.desc);
                Map<Integer, Long> keys = new HashMap<>();
                for (int i = 0; i < args.length; i++) {
                    if (isEncryptable(args[i])) {
                        long key = args[i].getSort() == Type.LONG ? nonZeroLong(rnd) : nonZeroInt(rnd);
                        keys.put(i, key);
                    }
                }
                if (!keys.isEmpty()) {
                    targetMethods.put(mn.name + mn.desc, keys);
                }
            }

            if (targetMethods.isEmpty()) {
                continue;
            }

            for (MethodNode mn : cn.methods) {
                Map<Integer, Long> keys = targetMethods.get(mn.name + mn.desc);
                if (keys == null) {
                    continue;
                }
                Type[] args = Type.getArgumentTypes(mn.desc);
                int slot = 0;
                InsnList decodeList = new InsnList();
                for (int i = 0; i < args.length; i++) {
                    Type t = args[i];
                    if (keys.containsKey(i)) {
                        long key = keys.get(i);
                        if (t.getSort() == Type.LONG) {
                            decodeList.add(new VarInsnNode(Opcodes.LLOAD, slot));
                            decodeList.add(new LdcInsnNode(key));
                            decodeList.add(new InsnNode(Opcodes.LXOR));
                            decodeList.add(new VarInsnNode(Opcodes.LSTORE, slot));
                        } else {
                            decodeList.add(new VarInsnNode(Opcodes.ILOAD, slot));
                            decodeList.add(new LdcInsnNode((int) key));
                            decodeList.add(new InsnNode(Opcodes.IXOR));
                            decodeList.add(new VarInsnNode(Opcodes.ISTORE, slot));
                        }
                    }
                    slot += t.getSize();
                }
                mn.instructions.insert(decodeList);
                mn.maxStack = Math.max(mn.maxStack, 4);
                totalMethods++;
            }

            for (MethodNode caller : cn.methods) {
                if (caller.instructions == null) {
                    continue;
                }
                List<MethodInsnNode> callsites = new ArrayList<>();
                for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        if (minsn.owner.equals(cn.name) && targetMethods.containsKey(minsn.name + minsn.desc)) {
                            callsites.add(minsn);
                        }
                    }
                }

                for (MethodInsnNode minsn : callsites) {
                    Map<Integer, Long> keys = targetMethods.get(minsn.name + minsn.desc);
                    Type[] args = Type.getArgumentTypes(minsn.desc);
                    int totalArgSlots = 0;
                    int[] slotOffsets = new int[args.length];
                    for (int a = 0; a < args.length; a++) {
                        slotOffsets[a] = totalArgSlots;
                        totalArgSlots += args[a].getSize();
                    }
                    int tempBase = caller.maxLocals;
                    caller.maxLocals += totalArgSlots;

                    InsnList popList = new InsnList();
                    for (int a = args.length - 1; a >= 0; a--) {
                        Type t = args[a];
                        popList.add(new VarInsnNode(t.getOpcode(Opcodes.ISTORE), tempBase + slotOffsets[a]));
                    }

                    InsnList pushList = new InsnList();
                    for (int a = 0; a < args.length; a++) {
                        Type t = args[a];
                        pushList.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), tempBase + slotOffsets[a]));
                        if (keys.containsKey(a)) {
                            long k = keys.get(a);
                            if (t.getSort() == Type.LONG) {
                                pushList.add(new LdcInsnNode(k));
                                pushList.add(new InsnNode(Opcodes.LXOR));
                            } else {
                                pushList.add(new LdcInsnNode((int) k));
                                pushList.add(new InsnNode(Opcodes.IXOR));
                            }
                        }
                    }
                    caller.instructions.insertBefore(minsn, popList);
                    caller.instructions.insertBefore(minsn, pushList);
                    caller.maxStack = Math.max(caller.maxStack, totalArgSlots + 4);
                    totalCallsites++;
                }
            }
        }

        ctx.log().debug("parameterEncryption: encrypted parameters in " + totalMethods + " methods across " + totalCallsites + " callsites");
    }

    private static boolean canEncrypt(ClassNode cn, MethodNode mn, ObfContext ctx) {
        if (mn.name.startsWith("<")
                || (mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0
                || (mn.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                || mn.instructions == null
                || mn.instructions.size() < 2
                || Limits.oversizeMethod(mn)) {
            return false;
        }
        for (Type arg : Type.getArgumentTypes(mn.desc)) {
            if (isEncryptable(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEncryptable(Type type) {
        int sort = type.getSort();
        return sort == Type.INT
                || sort == Type.LONG
                || sort == Type.BOOLEAN
                || sort == Type.BYTE
                || sort == Type.CHAR
                || sort == Type.SHORT;
    }

    private static long nonZeroLong(Random rnd) {
        long v;
        do {
            v = rnd.nextLong();
        } while (v == 0L);
        return v;
    }

    private static int nonZeroInt(Random rnd) {
        int v;
        do {
            v = rnd.nextInt();
        } while (v == 0);
        return v;
    }
}
