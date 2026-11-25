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
        String fieldPrefix = sec.getString("fieldPrefix", "NULL").trim();
        if (fieldPrefix.isEmpty()) {
            fieldPrefix = "NULL";
        }
        boolean injectExisting = sec.getBoolean("injectExisting", false);
        boolean createClasses = sec.getBoolean("createClasses", !injectExisting);

        int version = Opcodes.V17;
        for (ClassNode c : ctx.classes()) {
            if (c.version > 0) {
                version = c.version;
                break;
            }
        }

        int createdCount = 0;
        if (createClasses) {
            createdCount = createBannerClasses(ctx, count, text, className, fieldPrefix, version);
        }

        int injectedCount = 0;
        if (injectExisting) {
            injectedCount = injectIntoExistingClasses(ctx, text, fieldPrefix);
        }

        ctx.log().debug("banner: createdClasses=" + createdCount
                + " injectedExisting=" + injectedCount
                + " (count=" + count + ")");
    }

    private int createBannerClasses(ObfContext ctx, int count, String text, String baseName, String fieldPrefix, int version) {
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

            for (int k = 0; k < lines.length; k++) {
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        fieldPrefix + "_" + k,
                        "Ljava/lang/String;",
                        null,
                        lines[k]));
            }

            ctx.putClass(cn);
            created++;
        }
        return created;
    }

    private int injectIntoExistingClasses(ObfContext ctx, String text, String fieldPrefix) {
        String[] lines = text.split("\r?\n");
        int injected = 0;
        List<ClassNode> targets = ctx.targets(id());
        for (ClassNode cn : targets) {
            if ((cn.access & (Opcodes.ACC_ANNOTATION | Opcodes.ACC_MODULE | Opcodes.ACC_INTERFACE)) != 0
                    || isMixinClass(cn)) {
                continue;
            }
            if (cn.fields == null) {
                cn.fields = new ArrayList<>();
            }
            for (int k = lines.length - 1; k >= 0; k--) {
                String fname = fieldPrefix + "_" + k;
                boolean exists = false;
                for (FieldNode fn : cn.fields) {
                    if (fn.name.equals(fname)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    cn.fields.add(0, new FieldNode(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                            fname,
                            "Ljava/lang/String;",
                            null,
                            lines[k]));
                }
            }
            injected++;
        }
        return injected;
    }

    private static boolean isMixinClass(ClassNode cn) {
        if (cn.invisibleAnnotations != null) {
            for (var ann : cn.invisibleAnnotations) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc)) {
                    return true;
                }
            }
        }
        if (cn.visibleAnnotations != null) {
            for (var ann : cn.visibleAnnotations) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc)) {
                    return true;
                }
            }
        }
        return false;
    }
}
