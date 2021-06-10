package cpw.mods.cl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class ModuleClassLoader extends ClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
        URL.setURLStreamHandlerFactory(ModularURLHandler.INSTANCE);
        ModularURLHandler.initFrom(ModuleClassLoader.class.getModule().getLayer());
    }

    private final Configuration configuration;
    private final Map<String, JarModuleFinder.JarModuleReference> resolvedRoots;
    private final Map<String, ResolvedModule> packageLookup;

    public ModuleClassLoader(final String name, final Configuration configuration) {
        super(name, null);
        this.configuration = configuration;
        record JarRef(ResolvedModule m, JarModuleFinder.JarModuleReference ref) {}
        this.resolvedRoots = configuration.modules().stream()
                .filter(m->m.reference() instanceof JarModuleFinder.JarModuleReference)
                .map(m->new JarRef(m, (JarModuleFinder.JarModuleReference)m.reference()))
                .collect(Collectors.toMap(r->r.m().name(), JarRef::ref));

        this.packageLookup = new HashMap<>();
        for (var mod : this.configuration.modules()) {
            mod.reference().descriptor().packages().forEach(pk->this.packageLookup.put(pk, mod));
        }
    }

    private URL readerToURL(final ModuleReader reader, final ModuleReference ref, final String name) {
        try {
            return ModuleClassLoader.toURL(reader.find(name));
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static URL toURL(final Optional<URI> uri) {
        if (uri.isPresent()) {
            try {
                return uri.get().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return null;
    }

    private Class<?> readerToClass(final ModuleReader reader, final ModuleReference ref, final String name) {
        var cname = name.replace('.','/')+".class";
        byte[] bytes;
        try (var is = reader.open(cname).orElseThrow(FileNotFoundException::new)) {
            bytes = is.readAllBytes();
        } catch (IOException ioe) {
            return null;
        }
        var modroot = this.resolvedRoots.get(ref.descriptor().name());
        var pkg = ProtectionDomainHelper.tryDefinePackage(this, name, modroot.jar().getManifest(), t->modroot.jar().getTrustedManifestEntries(t), this::definePackage);
        var cs = ProtectionDomainHelper.createCodeSource(toURL(ref.location()), modroot.jar().verifyAndGetSigners(name, bytes));
        return defineClass(name, bytes, 0, bytes.length, ProtectionDomainHelper.createProtectionDomain(cs, this));
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        final var pname = name.substring(0, name.lastIndexOf('.'));
        final var mname = Optional.ofNullable(this.packageLookup.get(pname)).map(ResolvedModule::name).orElse(null);
        if (mname != null) {
            return findClass(mname, name);
        } else {
            return super.findClass(name);
        }
    }

    private Package definePackage(final String[] args) {
        return definePackage(args[0], args[1], args[2], args[3], args[4], args[5], args[6], null);
    }

    @Override
    public URL getResource(final String name) {
        try {
            var reslist = findResourceList(name);
            if (!reslist.isEmpty()) {
                return reslist.get(0);
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected URL findResource(final String moduleName, final String name) throws IOException {
        try {
            return loadFromModule(moduleName, (reader, ref) -> this.readerToURL(reader, ref, name));
        } catch (UncheckedIOException ioe) {
            throw ioe.getCause();
        }
    }

    @Override
    public Enumeration<URL> getResources(final String name) throws IOException {
        return Collections.enumeration(findResourceList(name));
    }

    private List<URL> findResourceList(final String name) throws IOException {
        var idx = name.lastIndexOf('/');
        var pkgname =  (idx == -1 || idx==name.length()) ? "" : name.substring(0,idx).replace('/','.');
        var module = packageLookup.get(pkgname);
        if (module != null) {
            var res = findResource(module.name(), name);
            return res != null ? List.of(res): List.of();
        } else {
            return resolvedRoots.values().stream()
                    .map(JarModuleFinder.JarModuleReference::jar)
                    .map(jar->jar.findFile(name))
                    .map(ModuleClassLoader::toURL)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    @Override
    protected Enumeration<URL> findResources(final String name) throws IOException {
        return Collections.enumeration(findResourceList(name));
    }

    @Override
    protected Class<?> findClass(final String moduleName, final String name) {
        try {
            return loadFromModule(moduleName, (reader, ref) -> this.readerToClass(reader, ref, name));
        } catch (IOException e) {
            return null;
        }
    }

    private <T> T loadFromModule(final String moduleName, BiFunction<ModuleReader, ModuleReference, T> lookup) throws IOException {
        var module = configuration.findModule(moduleName).orElseThrow(FileNotFoundException::new);
        var ref = module.reference();
        try (var reader = ref.open()) {
            return lookup.apply(reader, ref);
        }
    }
}
