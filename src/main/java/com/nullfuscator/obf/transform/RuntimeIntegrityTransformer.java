package com.nullfuscator.obf.transform;

import com.nullfuscator.obf.core.*;
import com.nullfuscator.obf.util.BytecodeNoise;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public final class RuntimeIntegrityTransformer implements Transformer, Opcodes {

    private static final String TEMPLATE = "com/nullfuscator/obf/runtime/IntegrityGuard";

    @Override
    public String id() {
        return "runtimeIntegrity";
    }

    @Override
    public String description() {
        return "bind protected methods to mutually verified archive guards";
    }

    @Override
    public void transform(ObfContext ctx) {
        var section = ctx.config().section(id());
        List<String> rawInclude = section.getStringList("include");
        ExemptMatcher include;
        if (rawInclude.isEmpty() || rawInclude.contains("*")) {
            String main = Remapping.mainClassInternalName(ctx);
            if (main != null && ctx.getClass(main) != null && !ObfContext.isMixin(ctx.getClass(main))) {
                include = new ExemptMatcher(List.of(main));
            } else {
                include = new ExemptMatcher(List.of(""));
            }
        } else {
            include = new ExemptMatcher(rawInclude);
        }

        List<ClassNode> targets = ctx.targets(id()).stream()
                .filter(c -> !ObfContext.isMixin(c))
                .filter(c -> include.matches(ctx.originalName(c.name)) || include.matches(c.name))
                .filter(c -> (c.access & (ACC_ANNOTATION | ACC_INTERFACE)) == 0)
                .toList();
        if (targets.isEmpty()) {
            ctx.log().warn("runtimeIntegrity: no eligible classes matched, skipping integrity checks");
            return;
        }

        if (ctx.isModularJar()) {
            ctx.log().warn("runtimeIntegrity does not support modular JARs, skipping");
            return;
        }

        Set<String> targetPaths = new HashSet<>();
        for (var target : targets) {
            targetPaths.add(ctx.originalName(target.name) + ".class");
        }
        for (String path : ctx.resources().keySet()) {
            if (!path.startsWith("META-INF/versions/") || !path.endsWith(".class")) {
                continue;
            }
            int slash = path.indexOf('/', "META-INF/versions/".length());
            if (slash >= 0 && targetPaths.contains(path.substring(slash + 1))) {
                throw new IllegalArgumentException("runtimeIntegrity cannot protect versioned override: " + path);
            }
        }

        int count = Math.max(2, Math.min(8, section.getInt("guards", 3)));
        int percent = Math.max(0, Math.min(100, section.getInt("checkPercent", 10)));
        String resource = "META-INF/" + ctx.names().next() + ".bin";
        if (ctx.resources().containsKey(resource)) {
            throw new IllegalStateException("Integrity resource collision");
        }

        List<String> guards = new ArrayList<>();
        Map<String, String> guardCheckMethods = new HashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/" + TEMPLATE + ".class")) {
            if (in == null) {
                throw new IllegalStateException("Integrity template missing");
            }
            byte[] template = in.readAllBytes();
            for (int i = 0; i < count; i++) {
                String pkg = BytecodeNoise.randomPackage(ctx, "c");
                String name;
                do {
                    name = pkg + ctx.names().next();
                } while (ctx.getClass(name) != null);
                ctx.names().reserve(name);

                String checkMethod = ctx.names().next();
                guardCheckMethods.put(name, checkMethod);

                Map<String, String> remap = new HashMap<>();
                remap.put(TEMPLATE, name);
                remap.put(TEMPLATE + ".check()V", checkMethod);
                remap.put(TEMPLATE + ".verify(Ljava/lang/String;)V", ctx.names().next());
                remap.put(TEMPLATE + ".read(Ljava/lang/String;I)[B", ctx.names().next());
                remap.put(TEMPLATE + ".normalize([B)V", ctx.names().next());
                remap.put(TEMPLATE + ".digest([B)[B", ctx.names().next());
                remap.put(TEMPLATE + ".hex([B)Ljava/lang/String;", ctx.names().next());

                remap.put(TEMPLATE + ".PREFIX", ctx.names().next());
                remap.put(TEMPLATE + ".initialized", ctx.names().next());
                remap.put(TEMPLATE + ".failed", ctx.names().next());
                remap.put(TEMPLATE + ".deadline", ctx.names().next());
                remap.put(TEMPLATE + ".entries", ctx.names().next());
                remap.put(TEMPLATE + ".cursor", ctx.names().next());

                ClassNode copy = new ClassNode();
                new ClassReader(template).accept(new ClassRemapper(copy, new SimpleRemapper(remap)), 0);
                copy.version = V1_8;
                copy.sourceFile = null;
                for (var method : copy.methods) {
                    for (var insn : method.instructions) {
                        if (insn instanceof LdcInsnNode ldc && "#INDEX#".equals(ldc.cst)) {
                            ldc.cst = resource;
                        }
                    }
                }
                BytecodeNoise.decorateCarrier(copy, ctx);
                ctx.putClass(copy);
                guards.add(name);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read integrity template", e);
        }

        Set<String> monitored = new HashSet<>(guards);
        int sites = 0;
        for (var cn : targets) {
            monitored.add(cn.name);

            MethodNode init = cn.methods.stream()
                    .filter(m -> m.name.equals("<clinit>"))
                    .findFirst()
                    .orElse(null);
            if (init == null) {
                init = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
                init.instructions.add(new InsnNode(RETURN));
                cn.methods.add(init);
            }

            for (var method : cn.methods) {
                if ((method.access & (ACC_NATIVE | ACC_ABSTRACT)) != 0 || method.name.equals("<init>")) {
                    continue;
                }
                boolean decoder = method.desc.equals("(Ljava/lang/String;I)Ljava/lang/String;")
                        || method.desc.startsWith("(Ljava/lang/invoke/MethodHandles$Lookup;");
                if (!method.name.equals("<clinit>") && !decoder && ctx.random().nextInt(100) >= percent) {
                    continue;
                }
                if (Limits.oversizeMethod(method)) {
                    continue;
                }

                int first = ctx.random().nextInt(count);
                InsnList checks = new InsnList();
                for (int offset = 0; offset < 2; offset++) {
                    String guardName = guards.get((first + offset) % count);
                    String checkMethod = guardCheckMethods.get(guardName);
                    checks.add(new MethodInsnNode(
                            INVOKESTATIC,
                            guardName,
                            checkMethod,
                            "()V",
                            false));
                }
                method.instructions.insert(checks);
                sites++;
            }
        }

        ctx.integrityPlan(new IntegrityPlan(resource, Set.copyOf(monitored), Set.copyOf(guards)));
        ctx.log().info("runtimeIntegrity: " + monitored.size() + " classes, "
                + sites + " sites, " + count + " guards");
    }
}
