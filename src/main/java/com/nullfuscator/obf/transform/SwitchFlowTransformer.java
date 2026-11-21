package com.nullfuscator.obf.transform;

import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.core.Transformer;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.objectweb.asm.Opcodes.*;

public final class SwitchFlowTransformer implements Transformer {

    @Override
    public String id() {
        return "flowSwitch";
    }

    @Override
    public String description() {
        return "known-key switch dispatch";
    }

    private record Point(AbstractInsnNode insn, List<Integer> intLocals) {}

    private static final String[] JUNK_EXCEPTIONS = {
            "java/lang/IllegalStateException",
            "java/lang/IllegalArgumentException",
            "java/lang/NoSuchElementException",
            "java/lang/ArrayIndexOutOfBoundsException",
            "java/lang/UnsupportedOperationException",
            "java/lang/ArithmeticException",
            "java/lang/ConcurrentModificationException",
            "java/lang/SecurityException",
            "java/lang/RuntimeException"
    };

    @Override
    public void transform(ObfContext ctx) {
        Random rnd = ctx.random();
        int methods = 0;
        int switches = 0;

        for (ClassNode cn : ctx.targets(id())) {
            for (MethodNode mn : cn.methods) {
                if (ctx.isHotPath(cn, mn)) {
                    continue;
                }
                if (skip(mn)) {
                    continue;
                }

                List<Point> points = emptyStackPoints(cn.name, mn);
                if (points.isEmpty()) {
                    continue;
                }
                Collections.shuffle(points, rnd);

                int take = Math.min(2, points.size());
                for (int i = 0; i < take; i++) {
                    insertSwitch(rnd, mn.instructions, points.get(i));
                    switches++;
                }
                methods++;
            }
        }

        ctx.log().debug("flowSwitch: " + switches + " switch wrappers across "
                + methods + " methods");
    }

    private static void insertSwitch(Random rnd, InsnList insns, Point pt) {
        int n = 3 + rnd.nextInt(3);
        int realIndex = rnd.nextInt(n);
        LabelNode real = new LabelNode();
        LabelNode junk = new LabelNode();
        LabelNode[] labels = new LabelNode[n];
        for (int k = 0; k < n; k++) {
            labels[k] = (k == realIndex) ? real : junk;
        }

        InsnList frag = new InsnList();
        int key;
        if (rnd.nextBoolean()) {
            key = realIndex;
            pushKey(frag, key, pt.intLocals(), rnd);
            frag.add(new TableSwitchInsnNode(0, n - 1, junk, labels));
        } else {
            int[] keys = new int[n];
            int cur = rnd.nextInt(20);
            for (int k = 0; k < n; k++) {
                keys[k] = cur;
                cur += 1 + rnd.nextInt(7);
            }
            key = keys[realIndex];
            pushKey(frag, key, pt.intLocals(), rnd);
            frag.add(new LookupSwitchInsnNode(junk, keys, labels));
        }

        frag.add(junk);
        frag.add(junkThrow(rnd));
        frag.add(real);
        insns.insertBefore(pt.insn(), frag);
    }

    private static void pushKey(InsnList il, int key, List<Integer> intLocals, Random rnd) {
        if (!intLocals.isEmpty() && rnd.nextInt(100) < 70) {
            int slot = intLocals.get(rnd.nextInt(intLocals.size()));
            int flavor = rnd.nextInt(4);
            switch (flavor) {
                case 0 -> {
                    il.add(new VarInsnNode(ILOAD, slot));
                    pushInt(il, key);
                    il.add(new InsnNode(IXOR));
                    il.add(new VarInsnNode(ILOAD, slot));
                    il.add(new InsnNode(IXOR));
                }
                case 1 -> {
                    il.add(new VarInsnNode(ILOAD, slot));
                    pushInt(il, key);
                    il.add(new InsnNode(IADD));
                    il.add(new VarInsnNode(ILOAD, slot));
                    il.add(new InsnNode(ISUB));
                }
                case 2 -> {
                    il.add(new VarInsnNode(ILOAD, slot));
                    il.add(new InsnNode(ICONST_2));
                    il.add(new InsnNode(IMUL));
                    il.add(new VarInsnNode(ILOAD, slot));
                    il.add(new InsnNode(ICONST_1));
                    il.add(new InsnNode(ISHL));
                    il.add(new InsnNode(ISUB));
                    pushInt(il, key);
                    il.add(new InsnNode(IADD));
                }
                default -> {
                    il.add(new VarInsnNode(ILOAD, slot));
                    il.add(new VarInsnNode(ILOAD, slot));
                    il.add(new InsnNode(IXOR));
                    pushInt(il, key);
                    il.add(new InsnNode(IADD));
                }
            }
        } else {
            int a = rnd.nextInt() | 1;
            int inv = modInverseInt(a);
            pushInt(il, key);
            pushInt(il, a);
            il.add(new InsnNode(IMUL));
            pushInt(il, inv);
            il.add(new InsnNode(IMUL));
        }
    }

    private static int modInverseInt(int a) {
        int inv = a;
        inv *= 2 - a * inv;
        inv *= 2 - a * inv;
        inv *= 2 - a * inv;
        inv *= 2 - a * inv;
        return inv;
    }

    private static boolean skip(MethodNode mn) {
        if ((mn.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            return true;
        }
        if (mn.name.equals("<clinit>") || mn.name.equals("<init>")) {
            return true;
        }
        if (mn.instructions == null || mn.instructions.size() == 0) {
            return true;
        }
        if (com.nullfuscator.obf.core.Limits.oversizeMethod(mn)) {
            return true;
        }
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            return true;
        }
        return false;
    }

    private static List<Point> emptyStackPoints(String owner, MethodNode mn) {
        List<Point> pts = new ArrayList<>();
        try {
            Analyzer<BasicValue> a = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = a.analyze(owner, mn);
            AbstractInsnNode[] arr = mn.instructions.toArray();

            for (int i = 0; i < arr.length; i++) {
                Frame<BasicValue> f = frames[i];
                if (f == null || f.getStackSize() != 0 || arr[i].getOpcode() < 0) {
                    continue;
                }
                List<Integer> intLocals = new ArrayList<>();
                for (int l = 0; l < f.getLocals(); l++) {
                    BasicValue v = f.getLocal(l);
                    if (v != null && v == BasicValue.INT_VALUE) {
                        intLocals.add(l);
                    }
                }
                pts.add(new Point(arr[i], intLocals));
            }
        } catch (AnalyzerException | RuntimeException e) {
            AbstractInsnNode first = firstReal(mn);
            if (first != null) {
                pts.add(new Point(first, List.of()));
            }
        }
        return pts;
    }

    private static AbstractInsnNode firstReal(MethodNode mn) {
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                return n;
            }
        }
        return null;
    }

    private static InsnList junkThrow(Random rnd) {
        String exc = JUNK_EXCEPTIONS[rnd.nextInt(JUNK_EXCEPTIONS.length)];
        InsnList il = new InsnList();
        il.add(new TypeInsnNode(NEW, exc));
        il.add(new InsnNode(DUP));
        il.add(new MethodInsnNode(INVOKESPECIAL, exc, "<init>", "()V", false));
        il.add(new InsnNode(ATHROW));
        return il;
    }

    private static void pushInt(InsnList il, int v) {
        if (v >= -1 && v <= 5) {
            il.add(new InsnNode(ICONST_0 + v));
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            il.add(new IntInsnNode(BIPUSH, v));
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            il.add(new IntInsnNode(SIPUSH, v));
        } else {
            il.add(new LdcInsnNode(Integer.valueOf(v)));
        }
    }
}