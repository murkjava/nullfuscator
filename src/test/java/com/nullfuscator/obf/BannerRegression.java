package com.nullfuscator.obf;

import com.nullfuscator.obf.core.ObfConfig;
import com.nullfuscator.obf.core.ObfContext;
import com.nullfuscator.obf.transform.BannerTransformer;
import com.nullfuscator.obf.util.NameGenerator;
import com.nullfuscator.obf.util.ObfLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class BannerRegression implements Opcodes {

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        testBannerGeneration();
        testSingleBanner();
        testInjectExisting();
        System.out.println("PASS banner generation, field injection, reflection execution");
    }

    private static void testBannerGeneration() throws Exception {
        ClassNode existing = new ClassNode();
        existing.version = V17;
        existing.access = ACC_PUBLIC;
        existing.name = "sample/App";
        existing.superName = "java/lang/Object";

        MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "()I", null, null);
        m.instructions.add(new InsnNode(ICONST_1));
        m.instructions.add(new InsnNode(IRETURN));
        existing.methods.add(m);

        String bannerText = "Protected by Nullfuscator\nDiscord: discord.gg/test";
        String configStr = "banner {\n"
                + "  enabled: true\n"
                + "  count: 3\n"
                + "  className: \"CustomBanner\"\n"
                + "  injectExisting: false\n"
                + "  text: \"\"\"" + bannerText + "\"\"\"\n"
                + "}\n";

        ObfConfig config = ObfConfig.parse(configStr);
        ObfContext ctx = new ObfContext(config, 42, new ObfLog(false), new NameGenerator(new String[] { "I", "l" }));
        ctx.putClass(existing);
        ctx.initializePolicies();

        BannerTransformer transformer = new BannerTransformer();
        transformer.transform(ctx);

        check(ctx.getClass("CustomBanner_1") != null, "CustomBanner_1 must exist");
        check(ctx.getClass("CustomBanner_2") != null, "CustomBanner_2 must exist");
        check(ctx.getClass("CustomBanner_3") != null, "CustomBanner_3 must exist");

        Map<String, byte[]> byteMap = new HashMap<>();
        for (int i = 1; i <= 3; i++) {
            ClassNode cn = ctx.getClass("CustomBanner_" + i);
            check(cn != null, "Class must exist");

            boolean hasBannerField = false;
            boolean hasLine0 = false;
            boolean hasLine1 = false;
            boolean hasIndex = false;
            for (FieldNode fn : cn.fields) {
                if ("BANNER".equals(fn.name) && bannerText.equals(fn.value)) {
                    hasBannerField = true;
                }
                if ("LINE_0".equals(fn.name) && "Protected by Nullfuscator".equals(fn.value)) {
                    hasLine0 = true;
                }
                if ("LINE_1".equals(fn.name) && "Discord: discord.gg/test".equals(fn.value)) {
                    hasLine1 = true;
                }
                if ("BANNER_INDEX".equals(fn.name) && Integer.valueOf(i).equals(fn.value)) {
                    hasIndex = true;
                }
            }
            check(hasBannerField, "BANNER field must be present with correct value");
            check(hasLine0, "LINE_0 must match first line");
            check(hasLine1, "LINE_1 must match second line");
            check(hasIndex, "BANNER_INDEX must match index");

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            byteMap.put(cn.name, cw.toByteArray());
        }

        ClassLoader cl = new ClassLoader(BannerRegression.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = byteMap.get(name.replace('.', '/'));
                if (b != null) {
                    return defineClass(name, b, 0, b.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> banner1 = cl.loadClass("CustomBanner_1");
        Method getBanner = banner1.getMethod("getBanner");
        Object returned = getBanner.invoke(null);
        check(bannerText.equals(returned), "getBanner() must return banner text");

        Object instance = banner1.getConstructor().newInstance();
        check(bannerText.equals(instance.toString()), "toString() must return banner text");
    }

    private static void testSingleBanner() throws Exception {
        String bannerText = "Single Banner Text";
        String configStr = "banner {\n"
                + "  enabled: true\n"
                + "  count: 1\n"
                + "  className: \"Watermark\"\n"
                + "  text: \"" + bannerText + "\"\n"
                + "}\n";

        ObfConfig config = ObfConfig.parse(configStr);
        ObfContext ctx = new ObfContext(config, 42, new ObfLog(false), new NameGenerator(new String[] { "I", "l" }));
        ctx.initializePolicies();

        BannerTransformer transformer = new BannerTransformer();
        transformer.transform(ctx);

        check(ctx.getClass("Watermark") != null, "Watermark class must exist when count=1");
        check(ctx.getClass("Watermark_1") == null, "Watermark_1 must not exist when count=1");
    }

    private static void testInjectExisting() throws Exception {
        ClassNode existing = new ClassNode();
        existing.version = V17;
        existing.access = ACC_PUBLIC;
        existing.name = "sample/Existing";
        existing.superName = "java/lang/Object";

        String bannerText = "Injected Banner";
        String configStr = "banner {\n"
                + "  enabled: true\n"
                + "  count: 1\n"
                + "  createClasses: false\n"
                + "  injectExisting: true\n"
                + "  text: \"" + bannerText + "\"\n"
                + "}\n";

        ObfConfig config = ObfConfig.parse(configStr);
        ObfContext ctx = new ObfContext(config, 42, new ObfLog(false), new NameGenerator(new String[] { "I", "l" }));
        ctx.putClass(existing);
        ctx.initializePolicies();

        BannerTransformer transformer = new BannerTransformer();
        transformer.transform(ctx);

        boolean hasInjectedBanner = false;
        for (FieldNode fn : existing.fields) {
            if ("BANNER".equals(fn.name) && bannerText.equals(fn.value)) {
                hasInjectedBanner = true;
                break;
            }
        }
        check(hasInjectedBanner, "Existing class must receive BANNER field");
    }
}
