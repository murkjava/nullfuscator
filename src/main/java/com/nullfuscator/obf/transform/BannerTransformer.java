package com.nullfuscator.obf.transform;

import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.core.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

public final class BannerTransformer implements Transformer {

    private static final String DEFAULT_TEXT =
            "=======================================================\n" +
            "               Protected by Nullfuscator               \n" +
            "=======================================================";

    @Override
    public String id() {
        return "banner";
    }

    @Override
    public String description() {
        return "generate banner classes and inject banner watermarks";
    }

    @Override
    public boolean isEnabled(ObfContext ctx) {
        return ctx.config().section("banner").enabled() || ctx.config().section("banners").enabled();
    }

    @Override
    public void transform(ObfContext ctx) {
        var sec = ctx.config().section("banner").present()
                ? ctx.config().section("banner")
                : ctx.config().section("banners");

        int count = Math.max(1, Math.min(1000, sec.getInt("count", 1)));
        String text = sec.getString("text", DEFAULT_TEXT);
        String className = sec.getString("className", "Banner").trim();
        if (className.isEmpty()) {
            className = "Banner";
        }
        boolean createClasses = sec.getBoolean("createClasses", true);
        boolean injectExisting = sec.getBoolean("injectExisting", false);

        int version = Opcodes.V17;
        for (ClassNode c : ctx.classes()) {
            if (c.version > 0) {
                version = c.version;
                break;
            }
        }

        int createdCount = 0;
        if (createClasses) {
            createdCount = createBannerClasses(ctx, count, text, className, version);
        }

        int injectedCount = 0;
        if (injectExisting) {
            injectedCount = injectIntoExistingClasses(ctx, count, text);
        }

        ctx.log().debug("banner: createdClasses=" + createdCount
                + " injectedExisting=" + injectedCount
                + " (count=" + count + ")");
    }

    private int createBannerClasses(ObfContext ctx, int count, String text, String baseName, int version) {
        String[] lines = text.split("\r?\n");
        int created = 0;

        for (int i = 1; i <= count; i++) {
            String name = (count == 1) ? baseName : (baseName + "_" + i);
            String uniqueName = name;
            int suffix = 1;
            while (ctx.getClass(uniqueName) != null) {
                uniqueName = name + "$" + (suffix++);
            }

            ClassNode cn = new ClassNode();
            cn.version = version;
            cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
            cn.name = uniqueName;
            cn.superName = "java/lang/Object";

            MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            init.instructions.add(new InsnNode(Opcodes.RETURN));
            init.maxStack = 1;
            init.maxLocals = 1;
            cn.methods.add(init);

            if (cn.fields == null) {
                cn.fields = new ArrayList<>();
            }

            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "BANNER_INDEX",
                    "I",
                    null,
                    Integer.valueOf(i)));

            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "BANNER_COUNT",
                    "I",
                    null,
                    Integer.valueOf(count)));

            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "BANNER",
                    "Ljava/lang/String;",
                    null,
                    text));

            for (int k = 0; k < lines.length; k++) {
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        "LINE_" + k,
                        "Ljava/lang/String;",
                        null,
                        lines[k]));
            }

            MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new LdcInsnNode(Integer.valueOf(i)));
            clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "BANNER_INDEX", "I"));
            clinit.instructions.add(new LdcInsnNode(Integer.valueOf(count)));
            clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "BANNER_COUNT", "I"));
            clinit.instructions.add(new LdcInsnNode(text));
            clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "BANNER", "Ljava/lang/String;"));
            for (int k = 0; k < lines.length; k++) {
                clinit.instructions.add(new LdcInsnNode(lines[k]));
                clinit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "LINE_" + k, "Ljava/lang/String;"));
            }
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            clinit.maxStack = 1;
            clinit.maxLocals = 0;
            cn.methods.add(clinit);

            MethodNode getBanner = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getBanner", "()Ljava/lang/String;", null, null);
            getBanner.instructions.add(new LdcInsnNode(text));
            getBanner.instructions.add(new InsnNode(Opcodes.ARETURN));
            getBanner.maxStack = 1;
            getBanner.maxLocals = 0;
            cn.methods.add(getBanner);

            MethodNode toStringM = new MethodNode(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
            toStringM.instructions.add(new LdcInsnNode(text));
            toStringM.instructions.add(new InsnNode(Opcodes.ARETURN));
            toStringM.maxStack = 1;
            toStringM.maxLocals = 1;
            cn.methods.add(toStringM);

            ctx.putClass(cn);
            created++;
        }
        return created;
    }

    private int injectIntoExistingClasses(ObfContext ctx, int count, String text) {
        int injected = 0;
        List<ClassNode> targets = ctx.targets(id());
        for (ClassNode cn : targets) {
            if (injected >= count) {
                break;
            }
            if ((cn.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE)) != 0) {
                continue;
            }
            if (cn.fields == null) {
                cn.fields = new ArrayList<>();
            }
            String fieldName = "BANNER";
            int idx = 1;
            boolean exists = false;
            for (FieldNode fn : cn.fields) {
                if (fn.name.equals(fieldName)) {
                    exists = true;
                    break;
                }
            }
            while (exists) {
                fieldName = "BANNER_" + (idx++);
                exists = false;
                for (FieldNode fn : cn.fields) {
                    if (fn.name.equals(fieldName)) {
                        exists = true;
                        break;
                    }
                }
            }
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    fieldName,
                    "Ljava/lang/String;",
                    null,
                    text));
            injected++;
        }
        return injected;
    }
}
